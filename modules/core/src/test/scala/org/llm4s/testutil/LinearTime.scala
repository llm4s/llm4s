package org.llm4s.testutil

import org.scalatest.Assertions.fail

import java.lang.management.ManagementFactory
import scala.annotation.tailrec
import scala.concurrent.duration.*

/**
 * Checks that an operation's cost grows linearly with its input, without an absolute wall-clock bound
 * that a slow or loaded runner - Windows, coverage instrumentation, a busy CI host - can exceed (#1709).
 *
 * The operation is timed on a small and a large input (typically four times the size), alternating
 * between the two, and each input's cost is the cheapest of several samples.
 *
 * The cost is the calling thread's CPU time where the JVM measures it, else wall time (also on a
 * virtual thread, for which the JVM reports no CPU time). Wall time is not linear in the work on a
 * loaded host: a run shorter than the scheduler's time slice often finishes inside one, while a longer
 * run is descheduled, waits behind the other runnable threads and is charged for it, and so is a pause
 * for a collection that a starved GC thread is slow to finish. CPU time counts only the thread's own
 * work. Noise only ever adds cost, so the cheapest sample is the closest to the operation's own. That
 * holds for the large input too: its median sample is never cheaper than its cheapest, so taking the
 * median could not catch a quadratic operation the cheapest lets through, only fail more linear ones.
 *
 * A clock is only as fine as its ticks: thread CPU time on Windows advances in steps of about 15.6 ms,
 * so a microsecond-scale input reads 0 and its 4x input a few ticks (#1735). Each sample therefore
 * repeats the operation a calibrated number of times: the repeat count doubles until one sample of the
 * small input reads at least [[targetFor]] - eight ticks of the clock - and the large input's samples
 * repeat it as often. On a nanosecond clock the floor is a few milliseconds.
 *
 * Calibration is a single sample, and a later one can run cheaper - the JIT has finished compiling, a
 * collection fell in the calibration sample - so a small input calibrated at five ticks read three on
 * Windows (#1745). So the measured samples are checked too: if any sample of the small input reads under
 * the target, the repeat count doubles and both inputs are measured again. The bound is only ever
 * applied to small samples that read at least the target, unless a cap stopped the doubling.
 *
 * A linear operation costs about `large / small` times as much on the large input; a quadratic one,
 * its square. With a 4x input the default bound of 8x sits halfway between linear (4x) and quadratic
 * (16x). The default `slack` added to the bound is two ticks of the clock.
 *
 * Why eight ticks: a sample read on a clock of tick `T` is within one tick of its true cost `s` (small)
 * or `l` (large), either way. Quantisation can therefore cost the check far more than the two-tick
 * slack: the small reading, one tick low, is multiplied by the 8x bound, so together with the large
 * reading one tick high the worst case is nine ticks. The sample size, not the slack, absorbs that. A
 * linear operation (`l = 4s`) fails only if `4s + T > 8(s - T) + 2T`, that is `s < 1.75T`; a quadratic
 * one (`l = 16s`) escapes only if `16s - T <= 8(s + T) + 2T`, that is `s <= 1.375T`. Every small sample
 * the bound is applied to reads at least eight ticks, so its true cost is more than seven: four times
 * the linear failure point and five times the quadratic escape point. In terms of the small reading
 * `r`, an operation whose large input costs `R` times the small one fails only if
 * `R(r + T) + T > 8r + 2T`, that is `r < (R - 1)T / (8 - R)`: at `r >= 8T` that tolerates any `R` up to
 * 7.2 - a linear operation with a large input's overhead well above 4x - where three ticks tolerated
 * 6.25. Each sample being a few ticks, on Windows's 15.6 ms clock the suites that use this stay within
 * seconds of their run on a fine clock.
 *
 * Every single run of the operation is also held to `hangGuard` in wall time, so a catastrophic
 * regression fails even where the ratio would not show it; calibration, and the doubling after a short
 * small sample, stop at `maxRepeats` or once a sample takes `hangGuard` in wall time, and a large
 * input's sample stops at the first of its `StopChecks` clock reads to exceed the bound. Both inputs'
 * samples read the clock at the same points, so neither pays an overhead the other does not.
 */
object LinearTime {

  /** A monotonic reading in nanoseconds of the cost a sample is charged. */
  private[testutil] trait Clock {
    def name: String
    def nanos(): Long

    /**
     * The size of the clock's step, as observed: the median of a few successive changes in its reading. A
     * single change can span several steps - the thread was charged two ticks between two reads (#1771) - or,
     * where the clock's resolution changes for a moment, less than one; the median absorbs a stray change
     * either way.
     */
    lazy val granularity: FiniteDuration = observedGranularity(this)
  }

  private val threads = ManagementFactory.getThreadMXBean

  private[testutil] object CpuClock extends Clock {
    val name          = "CPU"
    def nanos(): Long = threads.getCurrentThreadCpuTime
  }

  private[testutil] object WallClock extends Clock {
    val name          = "wall"
    def nanos(): Long = System.nanoTime()
  }

  /** The calling thread's CPU time where the JVM measures it for this thread, else wall time. */
  private[testutil] def currentClock(): Clock =
    if (threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled && CpuClock.nanos() >= 0) CpuClock
    else WallClock

  private[testutil] val GranularitySteps = 5
  private val GranularityBudget          = 1.second
  private val MinTarget                  = 5.millis
  private val TicksPerSample             = 8
  private val MaxTarget                  = 1.second
  private val StopChecks                 = 16L

  /**
   * The median of up to [[GranularitySteps]] successive changes in `clock`'s reading, observed within one
   * second of wall time; see [[medianStep]]. A clock that never moved within that second is at least that
   * coarse.
   */
  private[testutil] def observedGranularity(clock: Clock): FiniteDuration = {
    val deadline = System.nanoTime() + GranularityBudget.toNanos
    val changes  = Vector.newBuilder[Long]
    var steps    = 0
    var last     = clock.nanos()
    while (steps < GranularitySteps && System.nanoTime() < deadline) {
      val now = clock.nanos()
      if (now != last) {
        changes += math.max(1L, now - last)
        last = now
        steps += 1
      }
    }
    if (steps == 0) GranularityBudget else medianStep(changes.result())
  }

  /**
   * The clock's step from the observed, non-empty `changes` in its reading: their median, the upper of the
   * two middle ones for an even count.
   *
   * Any one change can be off either way, and the step is shared by every suite in the test JVM. A change
   * can span two ticks - the thread was charged two ticks between two reads - and the largest, or a single
   * change, read Windows's 15.6 ms clock as a 31.2 ms step, doubling the calibration target and every repeat
   * count calibrated to it (#1771). A change can also be less than a tick - Windows's timer resolution raised
   * for a moment by `timeBeginPeriod` - and the smallest read a 1 ms step among 15.6 ms ones: a target of
   * 8 ms, one tick of the real clock, which a small sample could meet at one tick, voiding the eight-tick
   * margins. The median of five absorbs two outliers in either direction, or one of each. With an even count
   * the upper middle is taken: a step read too large only makes samples longer, one read too small lets
   * the bound be applied to samples of too few ticks.
   */
  private[testutil] def medianStep(changes: Seq[Long]): FiniteDuration =
    changes.sorted.apply(changes.size / 2).nanos

  /** The least a sample of the small input should cost on `clock`: eight of its ticks, at least 5 ms. */
  private[testutil] def targetFor(clock: Clock): FiniteDuration =
    (clock.granularity * TicksPerSample.toLong).max(MinTarget).min(MaxTarget)

  /**
   * The cheapest sample on the small and on the large input, each `repeats` runs of the operation on
   * `clock`. A large input's sample stopped once past the bound (`largeStopped`) reads as what it had
   * cost by then, which is less than the whole sample would have. `remeasures` counts the times the
   * samples were measured again, at twice the repeats, because a small sample read under the target.
   */
  final case class Scaling(
    small: FiniteDuration,
    large: FiniteDuration,
    repeats: Long,
    clock: String,
    largeStopped: Boolean = false,
    remeasures: Int = 0
  ) {
    def ratio: Double = large.toNanos.toDouble / math.max(small.toNanos, 1L).toDouble

    /** The cost of one run of the operation on each input. */
    def smallPerRun: FiniteDuration = (small.toNanos / repeats).nanos
    def largePerRun: FiniteDuration = (large.toNanos / repeats).nanos

    override def toString: String = {
      val atLeast = if (largeStopped) "at least " else ""
      f"small ${small.toNanos / 1e6}%.2f ms, large $atLeast${large.toNanos / 1e6}%.2f ms ($clock time, $repeats " +
        f"runs a sample: ${smallPerRun.toNanos / 1e3}%.1f vs $atLeast${largePerRun.toNanos / 1e3}%.1f us a run), " +
        f"ratio $atLeast$ratio%.1f" + (if (remeasures > 0) s", after $remeasures re-measurements" else "")
    }
  }

  /**
   * Times `op` on `small` and `large` and fails unless the large input's cheapest sample cost at most
   * `maxRatio` times the small one's plus `slack` (by default two ticks of the clock), and no run took
   * `hangGuard` or longer. Returns the measurement, for a clue.
   */
  def assertLinear[A](
    label: String,
    small: A,
    large: A,
    maxRatio: Double = 8.0,
    slack: Option[FiniteDuration] = None,
    hangGuard: FiniteDuration = 10.seconds,
    warmups: Int = 3,
    runs: Int = 3
  )(op: A => Any): Scaling =
    assertLinearOn(currentClock(), label, small, large, maxRatio, slack, hangGuard, warmups, runs)(op)

  private[testutil] val DefaultMaxRepeats = 1L << 20

  /** [[assertLinear]] on a given clock: the seam that lets a spec simulate a coarse one. */
  private[testutil] def assertLinearOn[A](
    clock: Clock,
    label: String,
    small: A,
    large: A,
    maxRatio: Double = 8.0,
    slack: Option[FiniteDuration] = None,
    hangGuard: FiniteDuration = 10.seconds,
    warmups: Int = 3,
    runs: Int = 3,
    maxRepeats: Long = DefaultMaxRepeats
  )(op: A => Any): Scaling = {
    val allowance = slack.getOrElse(clock.granularity * 2L).toNanos

    /**
     * `repeats` runs of `op` on `input`: their cost on `clock`, their wall time, and whether the sample
     * stopped early, once past `stopAbove` nanoseconds.
     *
     * The runs go in `StopChecks` batches, and the clock is read once after each batch whether or not
     * the sample has a bound to stop at, so a sample of either input pays the same overhead a run. Reading
     * it after every run of the large input only - a thread-CPU-time read costs about half a microsecond -
     * made a sub-microsecond operation read several times its own ratio.
     */
    def sample(input: A, repeats: Long, stopAbove: Long = Long.MaxValue): (Long, FiniteDuration, Boolean) = {
      val batch     = math.max(1L, repeats / StopChecks)
      val start     = clock.nanos()
      val wallStart = System.nanoTime()
      var done      = 0L
      var cost      = 0L
      while (done < repeats && cost <= stopAbove) {
        val end = math.min(repeats, done + batch)
        while (done < end) {
          timed(label, hangGuard)(op(input))
          done += 1
        }
        cost = clock.nanos() - start
      }
      (cost, (System.nanoTime() - wallStart).nanos, done < repeats)
    }

    // JIT warm-up, alternating
    (1 to warmups).foreach { _ =>
      timed(label, hangGuard)(op(small))
      timed(label, hangGuard)(op(large))
    }

    // a sample of the small input under the target, unless a cap stops the doubling
    val target                                          = targetFor(clock).toNanos
    def short(sampled: (Long, FiniteDuration, Boolean)) = sampled._1 < target && sampled._2 < hangGuard

    // calibrate: double the repeats until a sample of the small input costs the target on this clock
    var repeats     = 1L
    var calibration = sample(small, repeats)
    while (short(calibration) && repeats < maxRepeats) {
      repeats = math.min(repeats * 2, maxRepeats)
      calibration = sample(small, repeats)
    }

    /**
     * The cheapest of `runs` samples of each input at `repeats`, or `None` as soon as a sample of the small
     * input reads under the target while the repeats can still double: the round would be discarded.
     */
    def measure(repeats: Long, remeasures: Int): Option[Scaling] = {
      val canDouble = repeats < maxRepeats
      var smallest  = Long.MaxValue
      var largest   = Long.MaxValue
      var stopped   = false
      var run       = 0
      var discarded = false
      while (run < runs && !discarded) {
        val smallSample = sample(small, repeats)
        if (canDouble && short(smallSample)) discarded = true
        else {
          smallest = math.min(smallest, smallSample._1)
          val bound               = (smallest * maxRatio).toLong + allowance
          val (cost, _, cutShort) = sample(large, repeats, stopAbove = bound)
          if (cost < largest) {
            largest = cost
            stopped = cutShort
          }
          run += 1
        }
      }
      if (discarded) None else Some(Scaling(smallest.nanos, largest.nanos, repeats, clock.name, stopped, remeasures))
    }

    // a later sample can run cheaper than calibration's (#1745): never judge a small sample under the target,
    // doubling the repeats and measuring both inputs again until a cap stops the doubling
    @tailrec def measureFrom(repeats: Long, remeasures: Int): Scaling = measure(repeats, remeasures) match {
      case Some(scaling) => scaling
      case None          => measureFrom(math.min(repeats * 2, maxRepeats), remeasures + 1)
    }
    val scaling = measureFrom(repeats, 0)
    if (scaling.large.toNanos > scaling.small.toNanos * maxRatio + allowance)
      fail(
        f"$label: not linear: $scaling, more than ${maxRatio}x the small input's cost plus ${allowance / 1e6}%.3f ms"
      )
    scaling
  }

  /** How long `body` took in wall time; fails if it took `hangGuard` or longer. */
  def timed(label: String, hangGuard: FiniteDuration = 10.seconds)(body: => Any): FiniteDuration = {
    val start = System.nanoTime()
    body
    val elapsed = (System.nanoTime() - start).nanos
    if (elapsed >= hangGuard) fail(s"$label: took ${elapsed.toMillis} ms, at least the $hangGuard hang guard")
    elapsed
  }
}
