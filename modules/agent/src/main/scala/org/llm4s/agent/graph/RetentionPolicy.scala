package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * How much of a thread's history [[Checkpointer.prune]] keeps, by age or by size. Every limit is
 * optional, and one that is not set removes nothing, so `RetentionPolicy()` keeps everything.
 *
 *  - `maxAge`: a checkpoint created more than `maxAge` ago, and an event recorded more than `maxAge`
 *    ago, is removed - by the store's clock, against the checkpoint's `createdAt` and the event's
 *    `timestamp`.
 *  - `maxCheckpoints`: only the newest `maxCheckpoints` checkpoints are kept.
 *  - `maxEvents`: only the newest `maxEvents` events are kept.
 *
 * Whatever the limits, the thread's latest checkpoint and its pending writes are never removed, so
 * pruning never changes what `start`, `recover` or `resume` does next. Pruning a checkpoint also
 * removes the events recorded before the oldest checkpoint kept; `maxAge` and `maxEvents` may remove
 * more events than that, including the latest checkpoint's. Removing events raises the thread's replay floor exactly as [[Checkpointer.compactEvents]]
 * does ([[GraphError.ReplayUnavailable]]).
 *
 * `apply` and the `with*` setters throw `IllegalArgumentException` for a non-positive `maxAge` or
 * `maxCheckpoints`, or a negative `maxEvents`; [[RetentionPolicy.of]] returns a `ValidationError`.
 */
final case class RetentionPolicy private (
  maxAge: Option[FiniteDuration],
  maxCheckpoints: Option[Int],
  maxEvents: Option[Int]
):
  def withMaxAge(age: FiniteDuration): RetentionPolicy         = RetentionPolicy(Some(age), maxCheckpoints, maxEvents)
  def withMaxAge(age: Option[FiniteDuration]): RetentionPolicy = RetentionPolicy(age, maxCheckpoints, maxEvents)
  def withMaxCheckpoints(n: Int): RetentionPolicy              = RetentionPolicy(maxAge, Some(n), maxEvents)
  def withMaxCheckpoints(n: Option[Int]): RetentionPolicy      = RetentionPolicy(maxAge, n, maxEvents)
  def withMaxEvents(n: Int): RetentionPolicy                   = RetentionPolicy(maxAge, maxCheckpoints, Some(n))
  def withMaxEvents(n: Option[Int]): RetentionPolicy           = RetentionPolicy(maxAge, maxCheckpoints, n)

object RetentionPolicy:
  private def problems(
    maxAge: Option[FiniteDuration],
    maxCheckpoints: Option[Int],
    maxEvents: Option[Int]
  ): List[String] =
    List(
      maxAge.filter(_.length <= 0).map(a => s"maxAge must be positive, was $a"),
      maxCheckpoints.filter(_ < 1).map(n => s"maxCheckpoints must be at least 1, was $n"),
      maxEvents.filter(_ < 0).map(n => s"maxEvents must not be negative, was $n")
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid limit; use [[of]] for untrusted input. */
  def apply(
    maxAge: Option[FiniteDuration] = None,
    maxCheckpoints: Option[Int] = None,
    maxEvents: Option[Int] = None
  ): RetentionPolicy =
    val found = problems(maxAge, maxCheckpoints, maxEvents)
    require(found.isEmpty, found.mkString("; "))
    new RetentionPolicy(maxAge, maxCheckpoints, maxEvents)

  def of(
    maxAge: Option[FiniteDuration] = None,
    maxCheckpoints: Option[Int] = None,
    maxEvents: Option[Int] = None
  ): Result[RetentionPolicy] =
    problems(maxAge, maxCheckpoints, maxEvents) match
      case Nil   => Right(new RetentionPolicy(maxAge, maxCheckpoints, maxEvents))
      case found => Left(ValidationError("retention", found))

  /** No limit: [[Checkpointer.prune]] removes nothing. */
  val keepAll: RetentionPolicy = apply()
