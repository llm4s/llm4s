// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError, SimpleError, TimeoutError }
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
 * The body is also bounded in size ([[https://github.com/llm4s/llm4s/issues/1846 #1846]]): [[readAll]] and
 * [[readLine]] refuse to hold more than `maxChars` characters, so a server cannot make the client buffer an
 * unbounded reply. Past the bound the stream is closed and the read is an error.
 *
 * Not thread-safe for reading: one thread reads, the timer only closes. Always [[close]] it.
 *
 * @param stream   the response body, as `Llm4sHttpClient.postStream` returned it
 * @param deadline when the whole exchange must be over; the request phase counts against it too
 * @param timeout  the configured timeout, for the error's message and `timeoutDuration`
 * @param url      the endpoint, for the error's message (its query string is never included)
 * @param maxChars the most characters [[readAll]] returns, or one [[readLine]] line holds
 */
final private[mcp] class BoundedResponseBody(
  stream: InputStream,
  deadline: Deadline,
  timeout: FiniteDuration,
  url: String,
  maxChars: Int = BoundedResponseBody.MaxReplyChars
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

  /**
   * The next line, without its terminator (`\n`, `\r` or `\r\n`, as `BufferedReader.readLine`); `None` at the
   * end of the body. A line longer than `maxChars` is an error.
   */
  def readLine(): Result[Option[String]] = guarded {
    val line = new java.lang.StringBuilder
    var c    = reader.read()
    while (c >= 0 && c != '\n' && c != '\r') {
      if (line.length >= maxChars) throw BoundedResponseBody.TooLarge
      line.append(c.toChar)
      c = reader.read()
    }
    if (c == '\r') {
      reader.mark(1)
      if (reader.read() != '\n') reader.reset()
    }
    if (c < 0 && line.length == 0) None else Some(line.toString)
  }

  /** The rest of the body; one longer than `maxChars` is an error. */
  def readAll(): Result[String] = guarded {
    val text   = new java.lang.StringBuilder
    val buffer = new Array[Char](8192)
    var read   = reader.read(buffer)
    while (read >= 0) {
      if (text.length + read > maxChars) throw BoundedResponseBody.TooLarge
      text.append(buffer, 0, read)
      read = reader.read(buffer)
    }
    text.toString
  }

  /** The error for a reply larger than `maxChars`; also closes the stream, as the rest is never read. */
  private[mcp] def tooLarge(): LLMError = {
    closeStream()
    SimpleError(s"POST ${HttpFailures.safeEndpoint(url)}: reply exceeds $maxChars characters")
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
        case BoundedResponseBody.TooLarge => Left(tooLarge())
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

  /** 10 MiB, as the bound `MCPServer` puts on a request body. */
  val MaxReplyChars: Int = 10 * 1024 * 1024

  /** Thrown inside a read past `maxChars`, and turned into [[BoundedResponseBody.tooLarge]] by `guarded`. */
  private case object TooLarge extends RuntimeException("reply too large", null, false, false)

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
