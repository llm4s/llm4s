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

  /**
   * A clock that only the operation advances, by the cost it charges, read in whole steps of `tick` from
   * `start`: a measurement on it is exact and repeatable.
   */
  final private class SimulatedClock(tick: FiniteDuration, start: FiniteDuration) extends LinearTime.Clock {
    private var elapsed                           = start.toNanos
    val name                                      = s"simulated, ${tick.toMicros} us ticks"
    override lazy val granularity: FiniteDuration = tick
    def nanos(): Long                             = elapsed / tick.toNanos * tick.toNanos
    def charge(cost: FiniteDuration): Unit        = elapsed += cost.toNanos
  }

  /** Windows's thread CPU clock: 15.625 ms ticks. */
  private val SimulatedTick = 15625.micros

  /**
   * An operation on the simulated clock whose large input (4) costs `largeRatio` times the small one (1), and
   * whose small input costs `cold` a run for its first `coldRuns` runs - warm-up and calibration - and `warm`
   * after: the JIT has finished compiling, so the samples measured are cheaper than the one calibrated (#1745).
   */
  private def cooling(clock: SimulatedClock, cold: FiniteDuration, warm: FiniteDuration, coldRuns: Int)(
    largeRatio: Double
  ): Long => Unit = {
    var smallRuns = 0
    units =>
      if (units == 1L) {
        clock.charge(if (smallRuns < coldRuns) cold else warm)
        smallRuns += 1
      } else clock.charge((warm.toNanos * largeRatio).toLong.nanos)
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

  /**
   * A clock that advances one `tick` every `readsPerTick` reads, except that the change numbered `jumpAt`
   * (from 0) spans two ticks: a read that landed late, as when the thread was charged two ticks of
   * Windows's CPU clock between two reads (#1771).
   */
  final private class JumpingClock(tick: FiniteDuration, readsPerTick: Int, jumpAt: Int) extends LinearTime.Clock {
    private var reads   = 0
    private var changes = 0
    private var reading = 0L
    val name            = s"jumping, ${tick.toMicros} us ticks"
    def nanos(): Long = {
      reads += 1
      if (reads % readsPerTick == 0) {
        reading += (if (changes == jumpAt) 2L else 1L) * tick.toNanos
        changes += 1
      }
      reading
    }
  }

  "LinearTime" should "observe one tick as a clock's step, however many ticks a single change in its reading spans" in {
    // Before #1771 the step was the largest change observed, so a two-tick change read as the step wherever it fell.
    (0 until LinearTime.GranularitySteps).foreach { jumpAt =>
      withClue(s"two-tick change at $jumpAt: ") {
        val clock = new JumpingClock(SimulatedTick, readsPerTick = 3, jumpAt = jumpAt)
        LinearTime.observedGranularity(clock) shouldBe SimulatedTick
        LinearTime.targetFor(clock) shouldBe SimulatedTick * 8L
      }
    }
  }

  it should "observe the step of a coarse clock and sample well above it" in {
    // The real clock, read in 15.6 ms steps: a change can span several steps however rarely, and the
    // smallest of several is one step on any healthy runner. Assert only what holds for any number of them.
    val clock = quantised(WindowsTick)
    val step  = clock.granularity
    withClue(s"observed step $step: ") {
      step should be > Duration.Zero
      (step.toNanos % WindowsTick.toNanos) shouldBe 0L
      step should be <= WindowsTick * 4L
    }
    LinearTime.targetFor(clock) should be >= WindowsTick * 8L
  }

  it should "observe a positive, bounded step on the real clock" in {
    val step = LinearTime.currentClock().granularity
    step should be > Duration.Zero
    // one tick of the coarsest clock this runs on, Windows's 15.6 ms, with room for a run of late reads
    step should be <= WindowsTick * 4L
  }

  it should "pass a linear workload of microseconds a run on a clock with 15.6 ms ticks" in {
    // Each run costs far less than a tick: timed once, the small input reads 0 and the large one 0 or a tick.
    val clock   = quantised(WindowsTick)
    val scaling = LinearTime.assertLinearOn(clock, "linear", 20000L, 80000L)(work)
    scaling.repeats should be > 1L
    // every small sample the bound was applied to read at least the target
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

  it should "measure again, never judging a small sample under its target, when samples run cheaper than calibration's" in {
    // #1745 on Windows: calibrated at five ticks, the small input's samples then read three - 46.88 ms against
    // 484.38 ms, ratio 10.3 - and the 8x bound applied to a three-tick reading failed linear work. Here the small
    // input costs 40 ms a run through warm-up and the first calibration samples and 30 ms after; the large input
    // costs 7x - linear, with more overhead than 4x. Calibrated at five ticks, as before, this stopped at 2 runs a
    // sample, which then read 3 ticks against 27 - 46.88 ms vs 421.88 ms, ratio 9.0 - and failed. Now a warm
    // sample under the eight-tick target is measured again at twice the repeats, and passes.
    val clock   = new SimulatedClock(SimulatedTick, start = Duration.Zero)
    val op      = cooling(clock, cold = 40.millis, warm = 30.millis, coldRuns = 6)(largeRatio = 7.0)
    val scaling = LinearTime.assertLinearOn(clock, "cheaper after calibration", 1L, 4L)(op)
    withClue(scaling.toString) {
      scaling.remeasures should be >= 1
      scaling.small should be >= LinearTime.targetFor(clock)
      scaling.ratio should be < 8.0
    }
  }

  it should "still fail a quadratic workload whose samples run cheaper than calibration's" in {
    val clock = new SimulatedClock(SimulatedTick, start = Duration.Zero)
    val op    = cooling(clock, cold = 40.millis, warm = 30.millis, coldRuns = 6)(largeRatio = 16.0)
    val failure = intercept[TestFailedException] {
      LinearTime.assertLinearOn(clock, "quadratic, cheaper after calibration", 1L, 4L)(op)
    }
    failure.getMessage should include("quadratic, cheaper after calibration: not linear")
    failure.getMessage should include("re-measurements")
  }

  it should "charge the small and the large input the same overhead a run, so a sub-microsecond op reads its own ratio" in {
    // The op's cost does not depend on its input: any ratio well above 1 is the harness's own overhead,
    // charged to one input and not the other - a clock read per run costs more than this op does (#1736).
    val scaling = LinearTime.assertLinear("constant", 1L, 4L)(_ => work(200))
    withClue(scaling.toString) {
      scaling.largePerRun.toNanos should be < 1.micro.toNanos
      scaling.ratio should be < 2.0
    }
  }

  it should "stop calibrating, and measuring again, at the repeat cap on a clock that never moves" in {
    val frozen = new LinearTime.Clock {
      val name          = "frozen"
      def nanos(): Long = 0L
    }
    val scaling = LinearTime.assertLinearOn(frozen, "frozen", 10L, 40L, maxRepeats = 64)(work)
    scaling.repeats shouldBe 64L
    scaling.remeasures shouldBe 0
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
