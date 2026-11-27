package org.llm4s.speech.util

import org.llm4s.error.CancelledError

import scala.sys.process.{ BasicIO, ProcessBuilder, ProcessLogger }
import scala.util.Try

/**
 * Runs a command-line program to completion, honouring interruption (design section 4.4).
 *
 * `scala.sys.process`'s `!` and `!!` do not: an interrupted wait throws `InterruptedException`, which
 * `Try` does not catch, so it escapes a `Result`-returning method - and the program is left running.
 * Here an interrupt destroys the program and comes back as `Left(InterruptedException)`, with the
 * thread's interrupt flag set again for the caller; the engine turns it into `CancelledError`.
 *
 * Every other outcome is what `!` and `!!` give: a program that cannot be started is an
 * `IOException`, and `stdout` reports a non-zero exit as a `RuntimeException` (what `!!` throws).
 */
private[speech] object CommandRuns {

  /** What the program wrote to stdout; a non-zero exit is a `RuntimeException`, as for `!!`. */
  def stdout(command: ProcessBuilder): Either[Throwable, String] = {
    val buffer = new StringBuffer
    start(command.run(BasicIO(withIn = false, buffer, None))).flatMap {
      case 0    => Right(buffer.toString)
      case code => Left(new RuntimeException(s"Nonzero exit value: $code"))
    }
  }

  /** How a program ended: its exit code and the last `maxStderrChars` characters it wrote to stderr. */
  final case class Completed(exitCode: Int, stderrTail: String)

  /**
   * Runs the program to completion, discarding its stdout and keeping the tail of its stderr, so an
   * engine can say why a run failed. A non-zero exit is a `Right` here: the caller reads `exitCode`.
   */
  def withStderrTail(command: ProcessBuilder, maxStderrChars: Int = 2000): Either[Throwable, Completed] = {
    val tail = new StderrTail(maxStderrChars)
    start(command.run(ProcessLogger(_ => (), tail.add))).map(code => Completed(code, tail.text))
  }

  /** The last `max` characters of the lines given to `add`. */
  final private class StderrTail(max: Int) {
    private val lines = scala.collection.mutable.ArrayDeque.empty[String]
    private var size  = 0

    def add(line: String): Unit = synchronized {
      lines.append(line)
      size += line.length + 1
      while (lines.size > 1 && size - lines.head.length - 1 >= max) size -= lines.removeHead().length + 1
    }

    def text: String = synchronized(lines.mkString("\n").takeRight(max))
  }

  /** The exit code, with the program's output going where `!` sends it. */
  def exitCode(command: ProcessBuilder): Either[Throwable, Int] =
    start(command.run(false))

  private def start(launch: => scala.sys.process.Process): Either[Throwable, Int] =
    Try(launch).toEither.flatMap { process =>
      CancelledError.catchInterrupt(process.exitValue()) match {
        case Left(interrupted) =>
          process.destroy()
          Thread.currentThread().interrupt()
          Left(interrupted)
        case Right(code) => Right(code)
      }
    }
}
