package org.llm4s.agent.graph

/**
 * The monotonic clock, in nanoseconds, by which a runtime bounds its waits on a store or a thread that may not
 * return: a lost claim's end ([[WaitClock.Bound.Loss]]) and its release ([[WaitClock.Bound.Release]]), a closing
 * wait that makes no progress ([[WaitClock.Bound.Close]]), a heartbeat's stop ([[WaitClock.Bound.Stop]]), a lost
 * run's writer ([[WaitClock.Bound.Drain]]), and a delegating call's wait on a busy child ([[WaitClock.Bound.Busy]]).
 * [[WaitClock.system]], `System.nanoTime`, in production. A test replaces it ([[GraphRuntime.waitClock]]) so that a
 * bound it names passes as soon as it is waited on, without waiting it out.
 *
 * A wait fixes its deadline as a [[now]] reading, and asks [[remaining]] how long is left until it, of its own
 * kind, each time it looks; it gives up once that is zero or less.
 */
private[agent] trait WaitClock:

  /** The clock's reading, in nanoseconds; only differences between readings mean anything. */
  def now(): Long

  /** How long, in nanoseconds, a wait of kind `bound` has left until `deadline`; zero or less once it has passed. */
  def remaining(deadline: Long, bound: WaitClock.Bound): Long

private[agent] object WaitClock:

  /** What a bounded wait is for. */
  enum Bound:
    /** A run, change or admission whose claim was found lost, bounded at twice `claims.ttl` after the loss. */
    case Loss

    /** The release of a lost claim, on a detached thread, waited for until the same bound as [[Loss]]. */
    case Release

    /** A closing wait - an `Async` drain, an `OnExit` exit commit - stuck on one commit for twice `claims.ttl`. */
    case Close

    /** A closing wait with no commit in flight: no deadline, only a pause before it looks again. */
    case Idle

    /** A heartbeat's stop: its beat and any renewal in flight, joined for at most `claims.ttl`. */
    case Stop

    /** A lost `Async` run's writer, with a commit in the store, waited for at most `claims.ttl`. */
    case Drain

    /** A delegating call's wait on a child another run holds, for twice the claim's life. */
    case Busy

  /** `System.nanoTime`, for every kind of wait. */
  val system: WaitClock = new WaitClock:
    def now(): Long                                   = System.nanoTime()
    def remaining(deadline: Long, bound: Bound): Long = deadline - System.nanoTime()
