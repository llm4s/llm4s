package org.llm4s.llmconnect

import java.time.Instant

/**
 * Runtime configuration for capturing raw LLM provider exchanges.
 *
 * Exchange logging is disabled by default. Applications can opt in by supplying
 * a [[ProviderExchangeSink]] through [[ProviderExchangeLogging.Enabled]] when
 * constructing a client.
 */
enum ProviderExchangeLogging:
  case Disabled
  case Enabled(sink: ProviderExchangeSink)

object ProviderExchangeLogging:
  def enabled(sink: ProviderExchangeSink): ProviderExchangeLogging =
    Enabled(sink)

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
 */
final case class ProviderExchange(
  exchangeId: String,
  provider: String,
  model: Option[String],
  requestId: Option[String],
  correlationId: Option[String],
  startedAt: Instant,
  completedAt: Instant,
  durationMs: Long,
  outcome: ProviderExchangeOutcome,
  requestBody: String,
  responseBody: Option[String],
  errorMessage: Option[String]
)
