package org.llm4s.agent.service

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How a worker holds its queue entries' leases (design 4.55): each lease lasts `ttl` from when the store grants or
 * renews it, by the store's clock, and is renewed every `renewEvery` - with the run's claim, W2 - so an entry whose
 * worker died comes back to the queue at most `ttl` after its last renewal. Keep `renewEvery` at most a third of
 * `ttl`, as the default does.
 *
 * Both durations must be positive and `renewEvery` shorter than `ttl`: `apply` and the `with*` setters throw
 * `IllegalArgumentException` otherwise, and [[LeasePolicy.of]] returns a `ValidationError`.
 */
@Experimental
final case class LeasePolicy private (ttl: FiniteDuration, renewEvery: FiniteDuration):
  def withTtl(t: FiniteDuration): LeasePolicy        = LeasePolicy(t, renewEvery)
  def withRenewEvery(r: FiniteDuration): LeasePolicy = LeasePolicy(ttl, r)

@Experimental
object LeasePolicy:
  private def problems(ttl: FiniteDuration, renewEvery: FiniteDuration): List[String] =
    List(
      Option.when(ttl.length <= 0)(s"ttl must be positive, was $ttl"),
      Option.when(renewEvery.length <= 0)(s"renewEvery must be positive, was $renewEvery"),
      Option.when(ttl.length > 0 && renewEvery.length > 0 && renewEvery >= ttl)(
        s"renewEvery ($renewEvery) must be shorter than ttl ($ttl)"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid policy; use [[of]] for untrusted input. */
  def apply(ttl: FiniteDuration = 30.seconds, renewEvery: FiniteDuration = 10.seconds): LeasePolicy =
    val found = problems(ttl, renewEvery)
    require(found.isEmpty, found.mkString("; "))
    new LeasePolicy(ttl, renewEvery)

  def of(ttl: FiniteDuration = 30.seconds, renewEvery: FiniteDuration = 10.seconds): Result[LeasePolicy] =
    problems(ttl, renewEvery) match
      case Nil   => Right(new LeasePolicy(ttl, renewEvery))
      case found => Left(ValidationError("queue.lease", found))

  /** A 30 second lease, renewed every 10 seconds: the same as a run's claim (`ClaimPolicy.default`). */
  val default: LeasePolicy = apply()

/**
 * The run queue's own settings (design 4.55), loaded from `llm4s.agent.queue` by
 * [[org.llm4s.config.RunQueueConfigLoader RunQueueConfigLoader]]:
 *
 *  - `maxAttempts`: the most attempts an entry makes before it is closed `Failed(AttemptsExhausted)` (default
 *    [[RunQueueSettings.DefaultMaxAttempts]]). Fixed on each entry at submission, so every worker agrees on it.
 *  - `ticketLifetime`: how long a closed entry - its input, attempts and outcome - is kept after it closed, by the
 *    store's clock, before the retention sweep reclaims it (default [[RunQueueSettings.DefaultTicketLifetime]]).
 *    Waiting on or following a reclaimed ticket answers [[QueueError.TicketExpired]].
 *  - `lease`: the [[LeasePolicy]] a worker leases entries with.
 *
 * `maxAttempts` must be at least 1 and `ticketLifetime` positive: `apply` and the `with*` setters throw
 * `IllegalArgumentException` otherwise, and [[RunQueueSettings.of]] returns a `ValidationError`.
 */
@Experimental
final case class RunQueueSettings private (maxAttempts: Int, ticketLifetime: FiniteDuration, lease: LeasePolicy):
  def withMaxAttempts(n: Int): RunQueueSettings               = RunQueueSettings(n, ticketLifetime, lease)
  def withTicketLifetime(t: FiniteDuration): RunQueueSettings = RunQueueSettings(maxAttempts, t, lease)
  def withLease(l: LeasePolicy): RunQueueSettings             = RunQueueSettings(maxAttempts, ticketLifetime, l)

@Experimental
object RunQueueSettings:

  /** Five attempts: a run that crashes its worker four times running is not a transient failure. */
  val DefaultMaxAttempts: Int = 5

  /** A day: long enough for a caller to come back for its result, short enough to keep the queue bounded. */
  val DefaultTicketLifetime: FiniteDuration = 24.hours

  private def problems(maxAttempts: Int, ticketLifetime: FiniteDuration): List[String] =
    List(
      Option.when(maxAttempts < 1)(s"maxAttempts must be at least 1, was $maxAttempts"),
      Option.when(ticketLifetime.length <= 0)(s"ticketLifetime must be positive, was $ticketLifetime")
    ).flatten

  /** Throws `IllegalArgumentException` for invalid settings; use [[of]] for untrusted input. */
  def apply(
    maxAttempts: Int = DefaultMaxAttempts,
    ticketLifetime: FiniteDuration = DefaultTicketLifetime,
    lease: LeasePolicy = LeasePolicy.default
  ): RunQueueSettings =
    val found = problems(maxAttempts, ticketLifetime)
    require(found.isEmpty, found.mkString("; "))
    new RunQueueSettings(maxAttempts, ticketLifetime, lease)

  def of(
    maxAttempts: Int = DefaultMaxAttempts,
    ticketLifetime: FiniteDuration = DefaultTicketLifetime,
    lease: LeasePolicy = LeasePolicy.default
  ): Result[RunQueueSettings] =
    problems(maxAttempts, ticketLifetime) match
      case Nil   => Right(new RunQueueSettings(maxAttempts, ticketLifetime, lease))
      case found => Left(ValidationError("queue", found))

  /** The defaults. */
  val default: RunQueueSettings = apply()
