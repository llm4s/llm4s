// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError, TimeoutError }
import org.llm4s.http.HttpFailures
import org.llm4s.types.Result

import java.io.{ BufferedReader, InputStream, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit }
import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.util.Try
import scala.util.control.NonFatal

/**
 * A response body read against the deadline of the exchange it belongs to ([[https://github.com/llm4s/llm4s/issues/1785 #1785]]).
 *
 * The JDK HTTP client's request timeout stops counting once the response headers arrive. A
 * Streamable HTTP server that answers with an SSE stream sends its headers at once and the
 * JSON-RPC response only when the tool has finished, so without a bound of its own the read would
 * last as long as the server liked, whatever [[MCPServerConfig.timeout]] said.
 *
 * When `deadline` passes, the stream is closed from a timer thread. A read blocked on it wakes, and
 * every read from then on is a [[TimeoutError]]. Closing the stream before its end also abandons the
 * connection, so the server sees the client leave. An interrupt of the reading thread stays a
 * [[CancelledError]], with the flag kept, as for any other `Llm4sHttpClient` stream read.
 *
 * Not thread-safe for reading: one thread reads, the timer only closes. Always [[close]] it.
 *
 * @param stream   the response body, as `Llm4sHttpClient.postStream` returned it
 * @param deadline when the whole exchange must be over; the request phase counts against it too
 * @param timeout  the configured timeout, for the error's message and `timeoutDuration`
 * @param url      the endpoint, for the error's message (its query string is never included)
 */
final private[mcp] class BoundedResponseBody(
  stream: InputStream,
  deadline: Deadline,
  timeout: FiniteDuration,
  url: String
) extends AutoCloseable {

  private val expired = new AtomicBoolean(false)
  private val closed  = new AtomicBoolean(false)
  private val reader  = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))

  private val alarm: ScheduledFuture[?] =
    BoundedResponseBody.timer.schedule(
      (() => {
        expired.set(true)
        closeStream()
      }): Runnable,
      math.max(0L, deadline.timeLeft.toNanos),
      TimeUnit.NANOSECONDS
    )

  /** The next line, without its terminator; `None` at the end of the body. */
  def readLine(): Result[Option[String]] = guarded(Option(reader.readLine()))

  /** The rest of the body. */
  def readAll(): Result[String] = guarded {
    val text   = new java.lang.StringBuilder
    val buffer = new Array[Char](8192)
    var read   = reader.read(buffer)
    while (read >= 0) {
      text.append(buffer, 0, read)
      read = reader.read(buffer)
    }
    text.toString
  }

  /** Stops the deadline's timer and closes the stream; closing before the end drops the connection. */
  override def close(): Unit = {
    alarm.cancel(false): Unit
    closeStream()
  }

  private def closeStream(): Unit =
    if (closed.compareAndSet(false, true)) Try(stream.close()): Unit

  private def guarded[A](read: => A): Result[A] =
    if (expired.get()) Left(timedOut(None))
    else
      try {
        val value = read
        // A closed JDK stream may report its end rather than fail: what follows the deadline is never a reply.
        if (expired.get()) Left(timedOut(None)) else Right(value)
      } catch {
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(HttpFailures.streamReadError(e, url, timeout))
        case NonFatal(e) if CancelledError.isCancellation(e) => Left(HttpFailures.streamReadError(e, url, timeout))
        case NonFatal(e) if expired.get()                    => Left(timedOut(Some(e)))
        case NonFatal(e)                                     => Left(HttpFailures.streamReadError(e, url, timeout))
      }

  private def timedOut(cause: Option[Throwable]): LLMError = {
    val endpoint = HttpFailures.safeEndpoint(url)
    TimeoutError(
      s"POST $endpoint: response timed out after $timeout (the server sent its headers but not a complete reply)",
      timeout,
      "mcp.streamable-http",
      cause
    ).withContext("endpoint", endpoint)
  }
}

private[mcp] object BoundedResponseBody {

  /** One daemon thread closes every expired body; a cancelled alarm is removed at once, not at its deadline. */
  private lazy val timer: ScheduledThreadPoolExecutor = {
    val factory: ThreadFactory = runnable => {
      val thread = new Thread(runnable, "llm4s-mcp-response-deadline")
      thread.setDaemon(true)
      thread
    }
    val executor = new ScheduledThreadPoolExecutor(1, factory)
    executor.setRemoveOnCancelPolicy(true)
    executor
  }
}
