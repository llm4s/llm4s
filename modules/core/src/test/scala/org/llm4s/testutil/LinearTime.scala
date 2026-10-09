package org.llm4s.testutil

import org.scalatest.Assertions.fail

import java.lang.management.ManagementFactory
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
 * work. Noise only ever adds cost, so the cheapest sample is the closest to the operation's own.
 *
 * A clock is only as fine as its ticks: thread CPU time on Windows advances in steps of about 15.6 ms,
 * so a microsecond-scale input reads 0 and its 4x input a few ticks (#1735). Each sample therefore
 * repeats the operation a calibrated number of times: the repeat count doubles until one sample of the
 * small input costs at least [[targetFor]] - about 13 ticks of the clock, so a tick's quantisation is
 * under 8% of the sample - and the large input's samples repeat it as often. On a nanosecond clock
 * the floor is a few milliseconds.
 *
 * A linear operation costs about `large / small` times as much on the large input; a quadratic one,
 * its square. With a 4x input the default bound of 8x sits halfway between linear (4x) and quadratic
 * (16x). The default `slack` added to the bound is two ticks of the clock, the most quantisation can
 * add to a reading of each input. Every single run of the operation is also held to `hangGuard` in wall
 * time, so a catastrophic regression fails even where the ratio would not show it; calibration stops
 * at `maxRepeats`, or once a sample takes `hangGuard` in wall time, and a large input's sample stops as
 * soon as it exceeds the bound.
 */
object LinearTime {

  /** A monotonic reading in nanoseconds of the cost a sample is charged. */
  private[testutil] trait Clock {
    def name: String
    def nanos(): Long

    /** The size of the clock's step, as observed: the largest of a few successive changes in its reading. */
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

  private val GranularitySteps  = 3
  private val GranularityBudget = 1.second
  private val MinTarget         = 5.millis
  private val TicksPerSample    = 13
  private val MaxTarget         = 1.second

  private[testutil] def observedGranularity(clock: Clock): FiniteDuration = {
    val deadline = System.nanoTime() + GranularityBudget.toNanos
    var largest  = 1L
    var steps    = 0
    var last     = clock.nanos()
    while (steps < GranularitySteps && System.nanoTime() < deadline) {
      val now = clock.nanos()
      if (now != last) {
        largest = math.max(largest, now - last)
        last = now
        steps += 1
      }
    }
    // a clock that never moved within the budget is at least that coarse
    if (steps == 0) GranularityBudget else largest.nanos
  }

  /** The least a sample of the small input should cost on `clock`: 13 of its ticks, at least 5 ms. */
  private[testutil] def targetFor(clock: Clock): FiniteDuration =
    (clock.granularity * TicksPerSample.toLong).max(MinTarget).min(MaxTarget)

  /**
   * The cheapest sample on the small and on the large input, each `repeats` runs of the operation on
   * `clock`. A large input's sample stopped once past the bound (`largeStopped`) reads as what it had
   * cost by then, which is less than the whole sample would have.
   */
  final case class Scaling(
    small: FiniteDuration,
    large: FiniteDuration,
    repeats: Long,
    clock: String,
    largeStopped: Boolean = false
  ) {
    def ratio: Double = large.toNanos.toDouble / math.max(small.toNanos, 1L).toDouble

    /** The cost of one run of the operation on each input. */
    def smallPerRun: FiniteDuration = (small.toNanos / repeats).nanos
    def largePerRun: FiniteDuration = (large.toNanos / repeats).nanos

    override def toString: String = {
      val atLeast = if (largeStopped) "at least " else ""
      f"small ${small.toNanos / 1e6}%.2f ms, large $atLeast${large.toNanos / 1e6}%.2f ms ($clock time, $repeats " +
        f"runs a sample: ${smallPerRun.toNanos / 1e3}%.1f vs $atLeast${largePerRun.toNanos / 1e3}%.1f us a run), " +
        f"ratio $atLeast$ratio%.1f"
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
     */
    def sample(input: A, repeats: Long, stopAbove: Long = Long.MaxValue): (Long, FiniteDuration, Boolean) = {
      val start     = clock.nanos()
      val wallStart = System.nanoTime()
      var done      = 0L
      var past      = false
      while (done < repeats && !past) {
        timed(label, hangGuard)(op(input))
        done += 1
        past = stopAbove != Long.MaxValue && clock.nanos() - start > stopAbove
      }
      (clock.nanos() - start, (System.nanoTime() - wallStart).nanos, done < repeats)
    }

    // JIT warm-up, alternating
    (1 to warmups).foreach { _ =>
      timed(label, hangGuard)(op(small))
      timed(label, hangGuard)(op(large))
    }

    // calibrate: double the repeats until a sample of the small input costs the target on this clock
    val target                                          = targetFor(clock).toNanos
    var repeats                                         = 1L
    var calibration                                     = sample(small, repeats)
    def short(sampled: (Long, FiniteDuration, Boolean)) = sampled._1 < target && sampled._2 < hangGuard
    while (short(calibration) && repeats < maxRepeats) {
      repeats = math.min(repeats * 2, maxRepeats)
      calibration = sample(small, repeats)
    }

    var smallest = Long.MaxValue
    var largest  = Long.MaxValue
    var stopped  = false
    (1 to runs).foreach { _ =>
      smallest = math.min(smallest, sample(small, repeats)._1)
      val bound               = (smallest * maxRatio).toLong + allowance
      val (cost, _, cutShort) = sample(large, repeats, stopAbove = bound)
      if (cost < largest) {
        largest = cost
        stopped = cutShort
      }
    }
    val scaling = Scaling(smallest.nanos, largest.nanos, repeats, clock.name, stopped)
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
