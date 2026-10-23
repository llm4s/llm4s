package org.llm4s.llmconnect

import org.llm4s.annotation.Stable

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
 * Runtime configuration for capturing raw LLM provider exchanges.
 *
 * Exchange logging is disabled by default. Applications can opt in by supplying
 * a [[ProviderExchangeSink]] through [[ProviderExchangeLogging.Enabled]] when
 * constructing a client.
 */
@Stable
enum ProviderExchangeLogging:
  case Disabled
  case Enabled(sink: ProviderExchangeSink)

object ProviderExchangeLogging:
  def enabled(sink: ProviderExchangeSink): ProviderExchangeLogging =
    Enabled(sink)

@Stable
enum ProviderExchangeOutcome:
  case Success
  case Error
  case Cancelled

/**
 * Minimal first-pass representation of a captured provider exchange.
 *
 * This intentionally starts small, but now carries enough timing and
 * correlation metadata to serve as a low-level debugging record that higher
 * level tracing or tooling can reference later.
 *
 * Build one with [[ProviderExchange.apply]] and change it with the `with*` setters (an `Option` field's setter
 * takes the value or an `Option`); the constructor and `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class ProviderExchange private (
  exchangeId: String,
  provider: String,
  model: Option[String],
  requestId: Option[String],
  correlationId: Option[String],
  startedAt: Instant,
  completedAt: Instant,
  duration: FiniteDuration,
  outcome: ProviderExchangeOutcome,
  requestBody: String,
  responseBody: Option[String],
  errorMessage: Option[String]
) {

  /** Sets the exchange's identifier. */
  def withExchangeId(exchangeId: String): ProviderExchange = copy(exchangeId = exchangeId)

  /** Sets the provider name. */
  def withProvider(provider: String): ProviderExchange = copy(provider = provider)

  /** Sets the model. */
  def withModel(model: String): ProviderExchange = copy(model = Some(model))

  /** Sets, or with `None` clears, the model. */
  def withModel(model: Option[String]): ProviderExchange = copy(model = model)

  /** Sets the provider's request id. */
  def withRequestId(requestId: String): ProviderExchange = copy(requestId = Some(requestId))

  /** Sets, or with `None` clears, the provider's request id. */
  def withRequestId(requestId: Option[String]): ProviderExchange = copy(requestId = requestId)

  /** Sets the caller's correlation id. */
  def withCorrelationId(correlationId: String): ProviderExchange = copy(correlationId = Some(correlationId))

  /** Sets, or with `None` clears, the caller's correlation id. */
  def withCorrelationId(correlationId: Option[String]): ProviderExchange = copy(correlationId = correlationId)

  /** Sets when the exchange started. */
  def withStartedAt(startedAt: Instant): ProviderExchange = copy(startedAt = startedAt)

  /** Sets when the exchange completed. */
  def withCompletedAt(completedAt: Instant): ProviderExchange = copy(completedAt = completedAt)

  /** Sets how long the exchange took. */
  def withDuration(duration: FiniteDuration): ProviderExchange = copy(duration = duration)

  /** Sets the exchange's outcome. */
  def withOutcome(outcome: ProviderExchangeOutcome): ProviderExchange = copy(outcome = outcome)

  /** Sets the raw request body. */
  def withRequestBody(requestBody: String): ProviderExchange = copy(requestBody = requestBody)

  /** Sets the raw response body. */
  def withResponseBody(responseBody: String): ProviderExchange = copy(responseBody = Some(responseBody))

  /** Sets, or with `None` clears, the raw response body. */
  def withResponseBody(responseBody: Option[String]): ProviderExchange = copy(responseBody = responseBody)

  /** Sets the error message. */
  def withErrorMessage(errorMessage: String): ProviderExchange = copy(errorMessage = Some(errorMessage))

  /** Sets, or with `None` clears, the error message. */
  def withErrorMessage(errorMessage: Option[String]): ProviderExchange = copy(errorMessage = errorMessage)
}

object ProviderExchange {

  /**
   * Creates a [[ProviderExchange]].
   *
   * @param exchangeId the exchange's identifier
   * @param provider the provider name
   * @param model the model
   * @param requestId the provider's request id
   * @param correlationId the caller's correlation id
   * @param startedAt when the exchange started
   * @param completedAt when the exchange completed
   * @param duration how long the exchange took
   * @param outcome the exchange's outcome
   * @param requestBody the raw request body
   * @param responseBody the raw response body
   * @param errorMessage the error message
   */
  def apply(
    exchangeId: String,
    provider: String,
    model: Option[String],
    requestId: Option[String],
    correlationId: Option[String],
    startedAt: Instant,
    completedAt: Instant,
    duration: FiniteDuration,
    outcome: ProviderExchangeOutcome,
    requestBody: String,
    responseBody: Option[String],
    errorMessage: Option[String]
  ): ProviderExchange =
    new ProviderExchange(
      exchangeId,
      provider,
      model,
      requestId,
      correlationId,
      startedAt,
      completedAt,
      duration,
      outcome,
      requestBody,
      responseBody,
      errorMessage
    )
}
