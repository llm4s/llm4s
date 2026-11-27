package org.llm4s.agent.graph

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

/**
 * A [[WaitClock]] for a test that must see a runtime give a wait up - a lost claim's end or release, a lost writer's
 * drain, a stalled closing wait, a busy child - without waiting the bound out (#3214). It is `System.nanoTime`, and
 * every wait runs on it as in production, except that once [[armed]], a wait of a kind in `passing` finds its
 * deadline already passed. So a bound passes only when the runtime waits on it - once whatever it bounds is stuck, the
 * test having made it so - never because a slow runner took long; and a wait of any other kind, in this runtime or
 * another, is not touched, as a clock moved forward for everyone would shorten it.
 *
 * A wait of a kind in `passing` before the clock is armed - when it could still be on something not yet stuck, such
 * as a closing wait on a healthy commit before the stuck one - looks again every few milliseconds, so that it passes
 * as soon as the test [[arm]]s the clock, once it has made the wait's object stuck.
 *
 * [[skipped]] is, for each kind it passed, the longest it spared one wait of it: how long that wait had left when it
 * looked, so a test can still check the bound it would have waited.
 */
final class PassingWaitClock(passing: Set[WaitClock.Bound], armedAtStart: Boolean = true) extends WaitClock {
  private val spared       = new ConcurrentHashMap[WaitClock.Bound, java.lang.Long]()
  @volatile private var on = armedAtStart

  def now(): Long = System.nanoTime()

  def remaining(deadline: Long, bound: WaitClock.Bound): Long = {
    val left = deadline - System.nanoTime()
    if (!passing.contains(bound) || left <= 0) left
    else if (on) {
      spared.merge(bound, left, (a, b) => math.max(a, b)): Unit
      0L
    } else math.min(left, PassingWaitClock.Poll.toNanos)
  }

  /** From now on, a wait of a kind in `passing` passes its bound. */
  def arm(): Unit = on = true

  /** Whether waits of the kinds in `passing` pass their bounds yet. */
  def armed: Boolean = on

  /** The longest wait of `bound` this clock passed, as it was when it looked; `None` if it passed none. */
  def skipped(bound: WaitClock.Bound): Option[FiniteDuration] = Option(spared.get(bound)).map(_.longValue.nanos)
}

object PassingWaitClock {

  /** How often a wait of a passing kind looks again while the clock is not armed. */
  val Poll: FiniteDuration = 2.millis

  /** Gives `runtime` a [[PassingWaitClock]] passing `bounds`, armed; the clock. */
  def on(runtime: GraphRuntime, bounds: WaitClock.Bound*): PassingWaitClock = {
    val clock = new PassingWaitClock(bounds.toSet)
    runtime.waitClock = clock
    clock
  }
}
