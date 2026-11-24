package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

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

@Stable
enum ProviderExchangeOutcome:
  case Success
  case Error
  case Cancelled

/**
 * One captured exchange with a provider: the request body a client sent and what came back, with the timing and the
 * ids that let a trace or a support ticket refer to it. A low-level debugging record: the bodies are the provider's
 * wire format, not llm4s types.
 *
 * Build one with [[ProviderExchange.apply]] and change it with the `with*` setters (an `Option` field's setter
 * takes the value or an `Option`); the constructor and `copy` are private, so a new field never breaks callers.
 *
 * @param exchangeId    this exchange's own id, unique per record
 * @param provider      the provider the client called
 * @param model         the model asked for, when the client names one
 * @param requestId     the provider's id for the request, when it reported one
 * @param correlationId the caller's id for the work the request belongs to, when it gave one
 * @param startedAt     when the request was sent
 * @param completedAt   when the reply, or the failure, was complete; [[duration]] is the time between the two
 * @param outcome       whether the call succeeded
 * @param requestBody   the request as sent
 * @param responseBody  the reply as received, when there was one
 * @param errorMessage  the error's message, when the call failed
 */
@Stable
final case class ProviderExchange private (
  exchangeId: String,
  provider: ProviderId,
  model: Option[String],
  requestId: Option[String],
  correlationId: Option[String],
  startedAt: Instant,
  completedAt: Instant,
  outcome: ProviderExchangeOutcome,
  requestBody: String,
  responseBody: Option[String],
  errorMessage: Option[String]
) {

  /** How long the exchange took: from [[startedAt]] to [[completedAt]]. */
  def duration: FiniteDuration = java.time.Duration.between(startedAt, completedAt).toScala

  /** Sets the exchange's identifier. */
  def withExchangeId(exchangeId: String): ProviderExchange = copy(exchangeId = exchangeId)

  /** Sets the provider. */
  def withProvider(provider: ProviderId): ProviderExchange = copy(provider = provider)

  /** Sets the model. */
  def withModel(model: String): ProviderExchange = copy(model = Some(model))

  /** Sets or clears the model. */
  def withModel(model: Option[String]): ProviderExchange = copy(model = model)

  /** Sets the provider's request id. */
  def withRequestId(requestId: String): ProviderExchange = copy(requestId = Some(requestId))

  /** Sets or clears the provider's request id. */
  def withRequestId(requestId: Option[String]): ProviderExchange = copy(requestId = requestId)

  /** Sets the caller's correlation id. */
  def withCorrelationId(correlationId: String): ProviderExchange = copy(correlationId = Some(correlationId))

  /** Sets or clears the caller's correlation id. */
  def withCorrelationId(correlationId: Option[String]): ProviderExchange = copy(correlationId = correlationId)

  /** Sets when the request was sent. */
  def withStartedAt(startedAt: Instant): ProviderExchange = copy(startedAt = startedAt)

  /** Sets when the exchange completed. */
  def withCompletedAt(completedAt: Instant): ProviderExchange = copy(completedAt = completedAt)

  /** Sets the outcome. */
  def withOutcome(outcome: ProviderExchangeOutcome): ProviderExchange = copy(outcome = outcome)

  /** Sets the request body. */
  def withRequestBody(requestBody: String): ProviderExchange = copy(requestBody = requestBody)

  /** Sets the response body. */
  def withResponseBody(responseBody: String): ProviderExchange = copy(responseBody = Some(responseBody))

  /** Sets or clears the response body. */
  def withResponseBody(responseBody: Option[String]): ProviderExchange = copy(responseBody = responseBody)

  /** Sets the error message. */
  def withErrorMessage(errorMessage: String): ProviderExchange = copy(errorMessage = Some(errorMessage))

  /** Sets or clears the error message. */
  def withErrorMessage(errorMessage: Option[String]): ProviderExchange = copy(errorMessage = errorMessage)
}

object ProviderExchange {

  /**
   * Creates a [[ProviderExchange]]. What every exchange has comes first; what one may lack defaults to `None`. Named
   * arguments are the supported way to build one.
   */
  def apply(
    exchangeId: String,
    provider: ProviderId,
    startedAt: Instant,
    completedAt: Instant,
    outcome: ProviderExchangeOutcome,
    requestBody: String,
    model: Option[String] = None,
    requestId: Option[String] = None,
    correlationId: Option[String] = None,
    responseBody: Option[String] = None,
    errorMessage: Option[String] = None
  ): ProviderExchange =
    new ProviderExchange(
      exchangeId,
      provider,
      model,
      requestId,
      correlationId,
      startedAt,
      completedAt,
      outcome,
      requestBody,
      responseBody,
      errorMessage
    )
}
