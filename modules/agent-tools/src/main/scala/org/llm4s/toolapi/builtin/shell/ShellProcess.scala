package org.llm4s.toolapi.builtin.shell

import org.llm4s.util.DurationRounding

import java.io.{ File, InputStream }
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Runs one command of [[ShellTool]] and leaves nothing of it behind (#3050).
 *
 *  - stdin is the null device, so a command that reads it with no file argument (`cat`, `wc`, `grep foo`) sees end of
 *    input at once instead of waiting out the timeout;
 *  - on every exit path - completion, timeout, or an interrupt of the calling thread (how `ToolExecutor` ends a call
 *    that timed out or was cancelled) - the process and every descendant it started are killed;
 *  - stdout and stderr are read by daemon threads into synchronised buffers, joined for a bounded time and their
 *    streams closed, so a reader blocked on a pipe that an escaped grandchild holds open never keeps the JVM alive.
 *
 * An interrupt is reported as [[ShellProcess.Interrupted]] with the thread's interrupt flag set again.
 */
private[shell] object ShellProcess {

  /** How long the output readers are waited for once the process has ended. */
  private val ReaderJoinMillis = 500L

  sealed trait Outcome

  final case class Finished(
    exitCode: Int,
    stdout: String,
    stderr: String,
    truncated: Boolean,
    timedOut: Boolean
  ) extends Outcome

  case object Interrupted extends Outcome

  /** The null device, `NUL` on Windows and `/dev/null` elsewhere. */
  private val NullDevice: File =
    new File(if (System.getProperty("os.name", "").toLowerCase.startsWith("windows")) "NUL" else "/dev/null")

  /** Starts `builder` with stdin from the null device and runs it to completion, timeout or interrupt. */
  def run(builder: ProcessBuilder, timeout: FiniteDuration, maxOutputSize: Int): Outcome = {
    builder.redirectErrorStream(false).redirectInput(Redirect.from(NullDevice))
    val process = builder.start()
    val stdout  = new Capture(process.getInputStream, maxOutputSize)
    val stderr  = new Capture(process.getErrorStream, maxOutputSize)
    stdout.start()
    stderr.start()

    // scalafix:off
    try {
      val completed = process.waitFor(DurationRounding.ceilMillis(timeout), TimeUnit.MILLISECONDS)
      val exitCode  = if (completed) process.exitValue() else -1
      killTree(process)
      stdout.finish()
      stderr.finish()
      Finished(
        exitCode = exitCode,
        stdout = stdout.text,
        stderr = stderr.text,
        truncated = stdout.truncated || stderr.truncated,
        timedOut = !completed
      )
    } catch {
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Interrupted
    } finally {
      killTree(process)
      stdout.close()
      stderr.close()
    }
    // scalafix:on
  }

  /**
   * Kills `process` and every process it started. The descendants are listed before the process is killed, because
   * once it has gone its children are re-parented and no longer listed as its descendants.
   */
  private[shell] def killTree(process: Process): Unit = {
    val descendants = Try(process.descendants().iterator().asScala.toList).getOrElse(Nil)
    process.destroyForcibly()
    descendants.foreach(handle => Try(handle.destroyForcibly()))
  }

  /** One output stream, read on a daemon thread into a buffer of at most `maxSize` characters. */
  final private class Capture(stream: InputStream, maxSize: Int) {
    private val buffer        = new java.lang.StringBuilder
    @volatile private var cut = false
    private val thread        = new Thread(() => read(), "llm4s-shell-output")
    thread.setDaemon(true)

    def start(): Unit = thread.start()

    /** Waits a bounded time for the reader; the stream's own end arrives once the process tree is gone. */
    def finish(): Unit = thread.join(ReaderJoinMillis)

    def close(): Unit = { val _ = Try(stream.close()) }

    def truncated: Boolean = cut

    def text: String = {
      val captured = buffer.synchronized(buffer.toString)
      if (cut) captured + "\n... (truncated)" else captured
    }

    // An IOException ends the read: the stream is closed when the process is killed or the call ends.
    private def read(): Unit = {
      val _ = Try {
        val chunk = new Array[Byte](1024)
        var n     = stream.read(chunk)
        while (n != -1) {
          buffer.synchronized {
            val room = maxSize - buffer.length
            if (room > 0) {
              val kept = Math.min(n, room)
              buffer.append(new String(chunk, 0, kept))
              if (kept < n) cut = true
            } else cut = true
          }
          n = stream.read(chunk)
        }
      }
    }
  }
}
