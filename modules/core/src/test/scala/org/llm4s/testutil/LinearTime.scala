package org.llm4s.testutil

import org.scalatest.Assertions.fail

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

/**
 * Checks that an operation's cost grows linearly with its input, without an absolute wall-clock bound
 * that a slow or loaded runner - Windows, coverage instrumentation, a busy CI host - can exceed (#1709).
 *
 * The operation is timed on a small and a large input (typically four times the size) after a JIT
 * warm-up, alternating between the two, and each input's cost is the cheapest of several runs.
 *
 * The cost is the calling thread's CPU time where the JVM measures it, else wall time. Wall time is
 * not linear in the work on a loaded host: a run shorter than the scheduler's time slice often
 * finishes inside one, while a longer run is descheduled, waits behind the other runnable threads and
 * is charged for it, and so is a pause for a collection that a starved GC thread is slow to finish.
 * CPU time counts only the thread's own work. Noise only ever adds cost, so the cheapest run is the
 * closest to the operation's own.
 *
 * A linear operation costs about `large / small` times as much on the large input; a quadratic one,
 * its square. With a 4x input the default bound of 8x sits halfway between linear (4x) and quadratic
 * (16x). `slack` is added to the bound so that a small input that costs microseconds - where the
 * timer's resolution dominates, about 15 ms for thread CPU time on Windows - does not fail a linear
 * operation; it is far below what a quadratic operation costs at the sizes the specs use. Every run is
 * also held to `hangGuard` in wall time, so a catastrophic regression fails even where the ratio
 * would not show it.
 */
object LinearTime {

  private val threads = ManagementFactory.getThreadMXBean

  private val cpuTime: Boolean =
    threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled

  /** The cheapest run on the small and on the large input, and their ratio. */
  final case class Scaling(small: FiniteDuration, large: FiniteDuration) {
    def ratio: Double = large.toNanos.toDouble / math.max(small.toNanos, 1L).toDouble

    override def toString: String = {
      val clock = if (cpuTime) "CPU" else "wall"
      f"small ${small.toNanos / 1e6}%.2f ms, large ${large.toNanos / 1e6}%.2f ms ($clock time), ratio $ratio%.1f"
    }
  }

  /**
   * Times `op` on `small` and `large` and fails unless the large input's cheapest run cost at most
   * `maxRatio` times the small one's plus `slack`, and no run took `hangGuard` or longer. Returns the
   * measurement, for a clue.
   */
  def assertLinear[A](
    label: String,
    small: A,
    large: A,
    maxRatio: Double = 8.0,
    slack: FiniteDuration = 50.millis,
    hangGuard: FiniteDuration = 10.seconds,
    warmups: Int = 3,
    runs: Int = 5
  )(op: A => Any): Scaling = {
    def cost(input: A): FiniteDuration = {
      val start = if (cpuTime) threads.getCurrentThreadCpuTime else 0L
      val wall  = timed(label, hangGuard)(op(input))
      if (cpuTime) (threads.getCurrentThreadCpuTime - start).nanos else wall
    }
    (1 to warmups).foreach { _ =>
      cost(small)
      cost(large)
    }
    val (smalls, larges) = (1 to runs).map(_ => (cost(small), cost(large))).unzip
    val scaling          = Scaling(smalls.min, larges.min)
    if (scaling.large.toNanos > scaling.small.toNanos * maxRatio + slack.toNanos)
      fail(s"$label: not linear: $scaling, more than ${maxRatio}x the small input's cost plus $slack")
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
