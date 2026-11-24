package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeOutcome }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.time.Instant
import scala.util.Try

/**
 * What a client knows about one provider call when it hands it to [[ProviderExchangeRecorder]]: who was called, when
 * the request went out, and the bodies. The recorder adds the id, the completion time and the outcome.
 *
 * Build one with [[ProviderExchangeCall.apply]] and change it with the `with*` setters (an `Option` field's setter
 * takes the value or an `Option`); the constructor and `copy` are private, so a new field never breaks callers.
 *
 * @param provider      the provider the client called
 * @param startedAt     when the request was sent
 * @param requestBody   the request as sent
 * @param model         the model asked for, when the client names one
 * @param responseBody  the reply as received, when there was one
 * @param requestId     the provider's id for the request, when it reported one
 * @param correlationId the caller's id for the work the request belongs to, when it gave one
 */
@Stable
final case class ProviderExchangeCall private (
  provider: ProviderId,
  startedAt: Instant,
  requestBody: String,
  model: Option[String],
  responseBody: Option[String],
  requestId: Option[String],
  correlationId: Option[String]
) {

  /** Sets the provider. */
  def withProvider(provider: ProviderId): ProviderExchangeCall = copy(provider = provider)

  /** Sets when the request was sent. */
  def withStartedAt(startedAt: Instant): ProviderExchangeCall = copy(startedAt = startedAt)

  /** Sets the request body. */
  def withRequestBody(requestBody: String): ProviderExchangeCall = copy(requestBody = requestBody)

  /** Sets the model. */
  def withModel(model: String): ProviderExchangeCall = copy(model = Some(model))

  /** Sets or clears the model. */
  def withModel(model: Option[String]): ProviderExchangeCall = copy(model = model)

  /** Sets the response body. */
  def withResponseBody(responseBody: String): ProviderExchangeCall = copy(responseBody = Some(responseBody))

  /** Sets or clears the response body. */
  def withResponseBody(responseBody: Option[String]): ProviderExchangeCall = copy(responseBody = responseBody)

  /** Sets the provider's request id. */
  def withRequestId(requestId: String): ProviderExchangeCall = copy(requestId = Some(requestId))

  /** Sets or clears the provider's request id. */
  def withRequestId(requestId: Option[String]): ProviderExchangeCall = copy(requestId = requestId)

  /** Sets the caller's correlation id. */
  def withCorrelationId(correlationId: String): ProviderExchangeCall = copy(correlationId = Some(correlationId))

  /** Sets or clears the caller's correlation id. */
  def withCorrelationId(correlationId: Option[String]): ProviderExchangeCall = copy(correlationId = correlationId)
}

object ProviderExchangeCall {

  /** Creates a [[ProviderExchangeCall]]. Named arguments are the supported way to build one. */
  def apply(
    provider: ProviderId,
    startedAt: Instant,
    requestBody: String,
    model: Option[String] = None,
    responseBody: Option[String] = None,
    requestId: Option[String] = None,
    correlationId: Option[String] = None
  ): ProviderExchangeCall =
    new ProviderExchangeCall(provider, startedAt, requestBody, model, responseBody, requestId, correlationId)
}

/** Records completed provider exchanges to the configured logging sink. */
@Stable
object ProviderExchangeRecorder {

  /**
   * Records `call`, which ended with `result`, to `exchangeLogging`'s sink; nothing when logging is disabled. The
   * exchange completes now, and is a `Success` or an `Error` as `result` is a `Right` or a `Left`. A sink that throws
   * never fails the call it records.
   */
  def record(exchangeLogging: ProviderExchangeLogging, call: ProviderExchangeCall, result: Result[?]): Unit =
    record(exchangeLogging, call, result, () => Instant.now(), () => java.util.UUID.randomUUID().toString)

  /** [[record]] with the clock and the id generator given: what a test fixes to assert on an exchange. */
  private[llm4s] def record(
    exchangeLogging: ProviderExchangeLogging,
    call: ProviderExchangeCall,
    result: Result[?],
    clock: () => Instant,
    idGenerator: () => String
  ): Unit =
    exchangeLogging match
      case ProviderExchangeLogging.Disabled => ()
      case ProviderExchangeLogging.Enabled(sink) =>
        val exchange = ProviderExchange(
          exchangeId = idGenerator(),
          provider = call.provider,
          startedAt = call.startedAt,
          completedAt = clock(),
          outcome = result.fold(_ => ProviderExchangeOutcome.Error, _ => ProviderExchangeOutcome.Success),
          requestBody = call.requestBody,
          model = call.model,
          requestId = call.requestId,
          correlationId = call.correlationId,
          responseBody = call.responseBody,
          errorMessage = result.left.toOption.map(_.message)
        )
        Try(sink.record(exchange)): Unit
}
