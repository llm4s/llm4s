package org.llm4s.speech.util

import org.llm4s.error.CancelledError

import scala.sys.process.{ BasicIO, ProcessBuilder }
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
