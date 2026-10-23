package org.llm4s.runner

import org.scalatest.TestSuite
import org.scalatest.concurrent.{ Signaler, ThreadSignaler, TimeLimitedTests }
import org.scalatest.time.{ Minutes, Span }

/**
 * A per-test time limit for specs that wait on futures, actors or threads (#2370), so a deadlock fails
 * the one test that hung, under its own name, instead of holding the CI job until its `timeout-minutes`.
 *
 * The limit is generous - healthy tests finish in seconds even on a slow Windows runner or under coverage
 * instrumentation - so it only catches a test that will never finish. On expiry the test's thread is
 * interrupted (`ThreadSignaler`), which wakes `Await.result`, `CountDownLatch.await`, `Future.get`,
 * `Thread.sleep` and the other interruptible waits, and the test fails with
 * `TestFailedDueToTimeoutException`. A spec that legitimately needs longer overrides `timeLimit`.
 *
 * A copy of `org.llm4s.testutil.TestTimeLimit` in core's tests, which this module's tests cannot see;
 * keep the two in step.
 */
trait TestTimeLimit extends TimeLimitedTests { this: TestSuite =>
  override def timeLimit: Span               = TestTimeLimit.DefaultLimit
  override val defaultTestSignaler: Signaler = ThreadSignaler
}

object TestTimeLimit {

  /** The default per-test limit. */
  val DefaultLimit: Span = Span(2, Minutes)
}
