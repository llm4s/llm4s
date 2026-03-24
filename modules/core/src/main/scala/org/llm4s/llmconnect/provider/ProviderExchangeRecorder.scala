package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeOutcome }
import org.llm4s.types.Result

import java.time.Instant
import scala.util.Try

private[provider] object ProviderExchangeRecorder {

  def record(
    exchangeLogging: ProviderExchangeLogging,
    provider: String,
    model: Option[String],
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?],
    requestId: Option[String] = None,
    correlationId: Option[String] = None
  ): Unit =
    exchangeLogging match
      case ProviderExchangeLogging.Disabled => ()
      case ProviderExchangeLogging.Enabled(sink) =>
        val completedAt = Instant.now()
        val exchange = ProviderExchange(
          exchangeId = java.util.UUID.randomUUID().toString,
          provider = provider,
          model = model,
          requestId = requestId,
          correlationId = correlationId,
          startedAt = startedAt,
          completedAt = completedAt,
          durationMs = java.time.Duration.between(startedAt, completedAt).toMillis,
          outcome = result.fold(_ => ProviderExchangeOutcome.Error, _ => ProviderExchangeOutcome.Success),
          requestBody = requestBody,
          responseBody = responseBody,
          errorMessage = result.left.toOption.map(_.message)
        )
        Try(sink.record(exchange))
}
