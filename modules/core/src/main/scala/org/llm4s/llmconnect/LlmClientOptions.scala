package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.metrics.MetricsCollector

/**
 * Client-construction options for [[LLMConnect]].
 *
 * This lets library users opt into runtime behaviors such as metrics, provider exchange logging and their own
 * HTTP transport without folding those concerns into [[org.llm4s.llmconnect.config.ProviderConfig]].
 *
 * Build one with [[LlmClientOptions.apply]] (or start from [[LlmClientOptions.default]]) and change it with the
 * `with*` setters; the constructor and `copy` are private, so a new option never breaks callers.
 *
 * @param metrics         collector the client reports request metrics to; no-op by default
 * @param exchangeLogging whether, and where, provider request/response exchanges are logged; disabled by default
 * @param httpClient      the HTTP client an SDK-free provider sends its requests through instead of building its
 *                        own; `None` (the default) builds one from the section's proxy and TLS settings. The caller
 *                        owns a supplied client: closing the LLM client does not close it, and the section's
 *                        `network` settings do not apply to it. A provider whose `ProviderFeatures.customHttpClient`
 *                        is `false` (the default; the SDK-based ones keep it) ignores it.
 */
@Stable
final case class LlmClientOptions private (
  metrics: MetricsCollector,
  exchangeLogging: ProviderExchangeLogging,
  httpClient: Option[Llm4sHttpClient]
):
  /** Sets the collector the client reports request metrics to. */
  def withMetrics(metrics: MetricsCollector): LlmClientOptions = copy(metrics = metrics)

  /** Sets whether, and where, provider exchanges are logged. */
  def withExchangeLogging(exchangeLogging: ProviderExchangeLogging): LlmClientOptions =
    copy(exchangeLogging = exchangeLogging)

  /** Sends the provider's HTTP requests through `httpClient`, which the caller keeps owning and closes. */
  def withHttpClient(httpClient: Llm4sHttpClient): LlmClientOptions = copy(httpClient = Some(httpClient))

  /** Sets the HTTP client to send requests through; `None` lets the provider build its own. */
  def withHttpClient(httpClient: Option[Llm4sHttpClient]): LlmClientOptions = copy(httpClient = httpClient)

object LlmClientOptions:

  /** Creates [[LlmClientOptions]]; with no arguments, no metrics, no exchange logging and the provider's own HTTP client. */
  def apply(
    metrics: MetricsCollector = MetricsCollector.noop,
    exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
    httpClient: Option[Llm4sHttpClient] = None
  ): LlmClientOptions =
    new LlmClientOptions(metrics, exchangeLogging, httpClient)

  /** Creates [[LlmClientOptions]] with the provider's own HTTP client. */
  def apply(metrics: MetricsCollector, exchangeLogging: ProviderExchangeLogging): LlmClientOptions =
    new LlmClientOptions(metrics, exchangeLogging, None)

  /** No metrics, no exchange logging and the provider's own HTTP client. */
  val default: LlmClientOptions = LlmClientOptions()
