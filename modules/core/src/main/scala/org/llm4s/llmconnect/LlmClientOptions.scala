package org.llm4s.llmconnect

import org.llm4s.metrics.MetricsCollector

/**
 * Client-construction options for [[LLMConnect]].
 *
 * This lets library users opt into runtime behaviors such as metrics and
 * provider exchange logging without folding those concerns into
 * [[org.llm4s.llmconnect.config.ProviderConfig]].
 */
final case class LlmClientOptions(
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)

object LlmClientOptions:
  val default: LlmClientOptions = LlmClientOptions()
