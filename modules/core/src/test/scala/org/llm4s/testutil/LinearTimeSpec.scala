package org.llm4s.testutil

import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** [[LinearTime]] on a simulated coarse clock, such as thread CPU time on Windows (#1735). */
class LinearTimeSpec extends AnyFlatSpec with Matchers {

  private val WindowsTick = 15600.micros

  /** The real per-thread clock, read in whole steps of `tick`. */
  private def quantised(tick: FiniteDuration): LinearTime.Clock = new LinearTime.Clock {
    private val underlying = LinearTime.currentClock()
    val name               = s"${underlying.name}, ${tick.toMicros} us ticks"
    def nanos(): Long      = underlying.nanos() / tick.toNanos * tick.toNanos
  }

  @volatile private var sink = 0L

  /** About `n` units of work. */
  private def work(n: Long): Unit = {
    var x = sink
    var i = 0L
    while (i < n) {
      x = x * 6364136223846793005L + i
      i += 1
    }
    sink = x
  }

  "LinearTime" should "observe the step of a coarse clock and sample well above it" in {
    val clock = quantised(WindowsTick)
    clock.granularity.toNanos shouldBe WindowsTick.toNanos +- 1.milli.toNanos
    LinearTime.targetFor(clock) should be >= 200.millis
  }

  it should "pass a linear workload of microseconds a run on a clock with 15.6 ms ticks" in {
    // Each run costs far less than a tick: timed once, the small input reads 0 and the large one 0 or a tick.
    val clock   = quantised(WindowsTick)
    val scaling = LinearTime.assertLinearOn(clock, "linear", 20000L, 80000L)(work)
    scaling.repeats should be > 1L
    scaling.small should be >= LinearTime.targetFor(clock)
    scaling.ratio should be < 8.0
  }

  it should "fail a quadratic workload of microseconds a run on a clock with 15.6 ms ticks" in {
    val clock = quantised(WindowsTick)
    val failure = intercept[TestFailedException] {
      LinearTime.assertLinearOn(clock, "quadratic", 200L, 800L, runs = 2)(n => work(n * n))
    }
    failure.getMessage should include("quadratic: not linear")
  }

  it should "stop calibrating at the repeat cap on a clock that never moves" in {
    val frozen = new LinearTime.Clock {
      val name          = "frozen"
      def nanos(): Long = 0L
    }
    val scaling = LinearTime.assertLinearOn(frozen, "frozen", 10L, 40L, maxRepeats = 64)(work)
    scaling.repeats shouldBe 64L
  }

  it should "fall back to wall time where the JVM reports no CPU time for the thread, as on a virtual thread" in {
    val result = new AtomicReference[Either[Throwable, (String, LinearTime.Scaling)]]()
    Thread
      .ofVirtual()
      .start { () =>
        result.set(
          scala.util
            .Try((LinearTime.currentClock().name, LinearTime.assertLinear("virtual", 20000L, 80000L)(work)))
            .toEither
        )
      }
      .join()
    val (clock, scaling) = result.get().fold(e => fail(e), identity)
    // JDK 21 reports no CPU time (-1) for a virtual thread
    clock shouldBe "wall"
    scaling.clock shouldBe "wall"
  }
}
