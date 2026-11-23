package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.util.DurationText

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * A call a circuit breaker refused without making it: the circuit is open, or it is half-open and another call is
 * already probing the service.
 *
 * It is the breaker's own answer, not the provider's: no request was sent, and nothing was learned about the
 * service. It is a [[RecoverableError]], since the same call can succeed once the circuit closes, with
 * [[retryAfter]] as its `retryDelay`. It is not retried automatically:
 * `org.llm4s.reliability.RetryPolicy.isRetryable` is `false` for it, because sending the call again at once is
 * what an open circuit exists to prevent. A caller that wants to wait reads [[retryAfter]]; one with another
 * provider falls back to it.
 *
 * @param message    what happened, naming the provider and the time left
 * @param provider   the name the breaker was given for the service it guards
 * @param retryAfter how long until the circuit lets a call through to probe the service; zero when a probe is
 *                   already in flight, since its result may close the circuit at any moment
 * @param probing    `true` when the circuit is half-open and the call was refused because a probe is in flight
 */
@Stable
final case class CircuitOpenError private (
  override val message: String,
  provider: String,
  retryAfter: FiniteDuration,
  probing: Boolean
) extends LLMError
    with RecoverableError {

  override def retryDelay: Option[FiniteDuration] = Some(retryAfter)

  override val context: Map[String, String] = Map(
    "provider"   -> provider,
    "retryAfter" -> DurationText(retryAfter),
    "probing"    -> probing.toString
  )
}

object CircuitOpenError {

  /** The circuit for `provider` is open, and stays so for `retryAfter` (read as zero when negative). */
  def open(provider: String, retryAfter: FiniteDuration): CircuitOpenError = {
    val remaining = retryAfter.max(Duration.Zero)
    new CircuitOpenError(
      s"Circuit breaker is open for $provider: the call was not made. Retry after ${DurationText(remaining)}",
      provider,
      remaining,
      probing = false
    )
  }

  /** The circuit for `provider` is half-open and a probe is already in flight. */
  def probing(provider: String): CircuitOpenError =
    new CircuitOpenError(
      s"Circuit breaker is half-open for $provider: a probe is already in progress, and the call was not made",
      provider,
      Duration.Zero,
      probing = true
    )
}
