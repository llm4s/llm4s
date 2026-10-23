package org.llm4s.testutil

import org.scalatest.{ Args, Reporter }
import org.scalatest.events.{ Event, TestFailed, TestSucceeded }
import org.scalatest.exceptions.TestFailedDueToTimeoutException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Span }

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch }
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters.*

class TestTimeLimitSpec extends AnyFlatSpec with Matchers {

  "TestTimeLimit" should "fail a test that never finishes, under that test's name, and interrupt it" in {
    val interrupted = new CountDownLatch(1)
    val inner       = new TestTimeLimitSpec.Hanging(interrupted)
    val events      = TestTimeLimitSpec.run(inner)

    val failed = events.collect { case f: TestFailed => f }
    failed.map(_.testName) shouldBe List("A hanging spec should wait forever")
    failed.head.throwable.get shouldBe a[TestFailedDueToTimeoutException]
    interrupted.getCount shouldBe 0L

    events.collect { case s: TestSucceeded => s.testName } shouldBe List("A hanging spec should finish in time")
  }

  it should "default to a limit that a healthy test on a slow runner never reaches" in {
    TestTimeLimit.DefaultLimit.totalNanos should be >= Span(60000, Millis).totalNanos
  }
}

object TestTimeLimitSpec {

  final class Hanging(interrupted: CountDownLatch) extends AnyFlatSpec with TestTimeLimit {
    override def timeLimit: Span = Span(200, Millis)

    "A hanging spec" should "wait forever" in {
      // Parks until interrupted: `LockSupport.park` returns on an interrupt instead of throwing.
      while (!Thread.currentThread().isInterrupted) LockSupport.park()
      interrupted.countDown()
    }

    it should "finish in time" in {
      succeed
    }
  }

  def run(suite: AnyFlatSpec): List[Event] = {
    val events = new ConcurrentLinkedQueue[Event]()
    val reporter = new Reporter {
      override def apply(event: Event): Unit = { events.add(event); () }
    }
    suite.run(None, Args(reporter))
    events.asScala.toList
  }
}
