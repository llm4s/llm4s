package org.llm4s.speech.processing

import org.scalatest.Assertions

import java.util.concurrent.{ Callable, Executors, ThreadFactory, TimeUnit, TimeoutException }
import scala.concurrent.duration.*
import scala.util.{ Failure, Success, Try }

/**
 * Runs a call on a daemon thread and gives up after a hard limit, so a call that never returns fails its test in
 * seconds instead of hanging the build.
 *
 * ScalaTest's `failAfter` is no substitute: it interrupts the calling thread and then waits for it, which does not
 * help against a loop that ignores interrupts, and that is exactly the failure these specs guard against.
 */
trait TimedCalls { self: Assertions =>

  private val daemons: ThreadFactory = task => {
    val thread = new Thread(task, "timed-call")
    thread.setDaemon(true)
    thread
  }

  protected def timed[A](limit: FiniteDuration = 5.seconds)(body: => A): A = {
    val executor = Executors.newSingleThreadExecutor(daemons)
    val future   = executor.submit(new Callable[A] { def call(): A = body })
    val outcome  = Try(future.get(limit.toMillis, TimeUnit.MILLISECONDS))
    future.cancel(true)
    executor.shutdownNow()
    outcome match {
      case Success(value)               => value
      case Failure(_: TimeoutException) => fail(s"the call did not return within $limit")
      case Failure(error)               => fail(s"the call threw $error", error)
    }
  }
}
