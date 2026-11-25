package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.util.DurationText

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * A call a circuit breaker refused without making it: the circuit is open, or it is half-open and another call is
 * already probing the service.
 *
 * It is the breaker's own answer, not the provider's: no request was sent, and nothing was learned about the
 * service. The same call can succeed once the circuit closes, but it is not [[LLMError.isRetryable retryable]]:
 * sending the call again at once is what an open circuit exists to prevent. A caller that wants to wait reads
 * [[remaining]]; one with another provider falls back to it. [[LLMError.retryAfter retryAfter]] is `None`: no
 * server asked for the delay.
 *
 * @param message    what happened, naming the provider and the time left
 * @param provider   the id of the service the breaker guards: the name the breaker was given, canonicalised as a
 *                   `ProviderId` is (trimmed, lower case)
 * @param remaining  how long until the circuit lets a call through to probe the service; zero when a probe is
 *                   already in flight, since its result may close the circuit at any moment
 * @param probing    `true` when the circuit is half-open and the call was refused because a probe is in flight
 */
@Stable
final case class CircuitOpenError private (
  override val message: String,
  provider: ProviderId,
  remaining: FiniteDuration,
  probing: Boolean
) extends LLMError {

  override val context: Map[String, String] = Map(
    "provider"  -> provider.asString,
    "remaining" -> DurationText(remaining),
    "probing"   -> probing.toString
  )
}

object CircuitOpenError {

  /** The circuit for `provider` is open, and stays so for `remaining` (read as zero when negative). */
  def open(provider: ProviderId, remaining: FiniteDuration): CircuitOpenError = {
    val left = remaining.max(Duration.Zero)
    new CircuitOpenError(
      s"Circuit breaker is open for $provider: the call was not made. Retry after ${DurationText(left)}",
      provider,
      left,
      probing = false
    )
  }

  /** The circuit for `provider` is half-open and a probe is already in flight. */
  def probing(provider: ProviderId): CircuitOpenError =
    new CircuitOpenError(
      s"Circuit breaker is half-open for $provider: a probe is already in progress, and the call was not made",
      provider,
      Duration.Zero,
      probing = true
    )

  /** Extracts the message, provider, `remaining` and `probing`; the arity is fixed, and it always matches. */
  def unapply(error: CircuitOpenError): Some[(String, ProviderId, FiniteDuration, Boolean)] =
    Some((error.message, error.provider, error.remaining, error.probing))
}
