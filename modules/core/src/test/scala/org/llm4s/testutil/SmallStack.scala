package org.llm4s.testutil

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a body on a thread with a small, fixed stack, so that a test about deeply nested input is
 * deterministic: an overflow is reported as a `Left(StackOverflowError)` instead of depending on the
 * JVM's default stack size, and never aborts the suite.
 *
 * The 1 MB default is the size `JSONToolSpec` in `llm4s-agent-tools` uses for the same purpose.
 */
object SmallStack {

  def run[A](body: => A, stackBytes: Long = 1024L * 1024L, timeoutMillis: Long = 60000L): Either[Throwable, A] = {
    val outcome = new AtomicReference[Option[A]](None)
    val thrown  = new AtomicReference[Option[Throwable]](None)
    val thread  = new Thread(null, () => outcome.set(Some(body)), "llm4s-small-stack", stackBytes)
    thread.setUncaughtExceptionHandler((_, e) => thrown.set(Some(e)))
    thread.start()
    thread.join(timeoutMillis)
    thrown.get().map(Left(_)).orElse(outcome.get().map(Right(_))).getOrElse {
      Left(new TimeoutException(s"body did not finish within ${timeoutMillis}ms"))
    }
  }
}
