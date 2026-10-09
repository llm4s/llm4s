package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How a [[GraphRuntime]]'s subscriptions learn of commits made through another runtime or process
 * sharing its [[Checkpointer]]. Commits made through the runtime itself are handed to its
 * subscriptions as they land, whatever this says.
 *
 * While `enabled`, each live subscription watches the store on a virtual thread of its own, named
 * `llm4s-watch-<threadId>`, calling [[Checkpointer.awaitEventsAfter]] with `pollInterval` as the
 * timeout, from the last event it has queued. A store that can be told of commits returns as soon as
 * one lands; one that cannot - the default, and the SQLite store - is read once per `pollInterval`,
 * so a commit made elsewhere reaches the subscription up to `pollInterval` after it lands. Only
 * durable events cross runtimes: live progress ([[StreamEvent.Live]]) is never stored, so it reaches
 * only the subscriptions of the runtime whose run sent it. When disabled, a subscription sees another
 * runtime's commits only by subscribing again, which replays the log; a runtime that knows it is the
 * only one using its store can disable it to save the watching thread and its reads.
 *
 * `pollInterval` must be positive: `apply` and the `with*` setters throw `IllegalArgumentException`
 * otherwise, and [[WatchPolicy.of]] returns a `ValidationError`.
 */
final case class WatchPolicy private (enabled: Boolean, pollInterval: FiniteDuration):
  def withEnabled(e: Boolean): WatchPolicy             = WatchPolicy(e, pollInterval)
  def withPollInterval(i: FiniteDuration): WatchPolicy = WatchPolicy(enabled, i)

object WatchPolicy:
  private def problems(pollInterval: FiniteDuration): List[String] =
    Option.when(pollInterval.length <= 0)(s"pollInterval must be positive, was $pollInterval").toList

  /** Throws `IllegalArgumentException` for a non-positive `pollInterval`; use [[of]] for untrusted input. */
  def apply(enabled: Boolean = true, pollInterval: FiniteDuration = 250.millis): WatchPolicy =
    val found = problems(pollInterval)
    require(found.isEmpty, found.mkString("; "))
    new WatchPolicy(enabled, pollInterval)

  def of(enabled: Boolean = true, pollInterval: FiniteDuration = 250.millis): Result[WatchPolicy] =
    problems(pollInterval) match
      case Nil   => Right(new WatchPolicy(enabled, pollInterval))
      case found => Left(ValidationError("watch", found))

  /** Subscriptions watch the store, waiting up to 250 milliseconds per call. */
  val default: WatchPolicy = apply()

  /** Subscriptions see only this runtime's commits live; others' by subscribing again. */
  val disabled: WatchPolicy = apply(enabled = false)
