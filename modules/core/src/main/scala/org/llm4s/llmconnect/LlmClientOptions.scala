package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.metrics.MetricsCollector

/**
 * Client-construction options for [[LLMConnect]].
 *
 * This lets library users opt into runtime behaviors such as metrics and
 * provider exchange logging without folding those concerns into
 * [[org.llm4s.llmconnect.config.ProviderConfig]].
 *
 * Build one with [[LlmClientOptions.apply]] (or start from [[LlmClientOptions.default]]) and change it with the
 * `with*` setters; the constructor and `copy` are private, so a new option never breaks callers.
 *
 * @param metrics         collector the client reports request metrics to; no-op by default
 * @param exchangeLogging whether, and where, provider request/response exchanges are logged; disabled by default
 */
@Stable
final case class LlmClientOptions private (
  metrics: MetricsCollector,
  exchangeLogging: ProviderExchangeLogging
):
  /** Sets the collector the client reports request metrics to. */
  def withMetrics(metrics: MetricsCollector): LlmClientOptions = copy(metrics = metrics)

  /** Sets whether, and where, provider exchanges are logged. */
  def withExchangeLogging(exchangeLogging: ProviderExchangeLogging): LlmClientOptions =
    copy(exchangeLogging = exchangeLogging)

object LlmClientOptions:

  /** Creates [[LlmClientOptions]]; with no arguments, no metrics and no exchange logging. */
  def apply(
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
  ): LlmClientOptions =
    new LlmClientOptions(metrics, exchangeLogging)

  /** No metrics and no exchange logging. */
  val default: LlmClientOptions = LlmClientOptions()
