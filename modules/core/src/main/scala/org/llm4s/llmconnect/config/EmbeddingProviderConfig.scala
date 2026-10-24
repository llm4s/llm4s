package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.util.Redaction

/**
 * Configuration required to connect to an embedding provider endpoint.
 *
 * @param baseUrl base URL of the embedding service (e.g. `https://api.openai.com/v1`)
 * @param model   name of the embedding model to use
 * @param apiKey  authentication key for the provider; redacted in `toString`
 * @param timeouts how long an embedding request may take: the section's `timeouts.request`. Absent
 *                 means the provider's own default (two minutes). `timeouts.stream` has no meaning for
 *                 an embedding call.
 * @param network  the proxy and TLS settings its HTTP calls use: `llm4s.http`. The default,
 *                 [[org.llm4s.http.HttpNetworkSettings.default]], is no proxy and the JDK's trust.
 *
 * Build one with [[EmbeddingProviderConfig.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingProviderConfig private (
  baseUrl: String,
  model: String,
  apiKey: String,
  timeouts: ProviderTimeouts,
  network: HttpNetworkSettings
) {

  /** Sets the base URL of the embedding service. */
  def withBaseUrl(baseUrl: String): EmbeddingProviderConfig = copy(baseUrl = baseUrl)

  /** Sets the embedding model. */
  def withModel(model: String): EmbeddingProviderConfig = copy(model = model)

  /** Sets the authentication key. */
  def withApiKey(apiKey: String): EmbeddingProviderConfig = copy(apiKey = apiKey)

  /** Sets how long an embedding request may take. */
  def withTimeouts(timeouts: ProviderTimeouts): EmbeddingProviderConfig = copy(timeouts = timeouts)

  /** Sets the proxy and TLS settings its HTTP calls use. */
  def withNetwork(network: HttpNetworkSettings): EmbeddingProviderConfig = copy(network = network)

  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}

object EmbeddingProviderConfig {

  /**
   * Creates an [[EmbeddingProviderConfig]]. `timeouts` defaults to [[ProviderTimeouts.default]], the provider's own
   * default, and `network` to [[org.llm4s.http.HttpNetworkSettings.default]], no proxy.
   */
  def apply(
    baseUrl: String,
    model: String,
    apiKey: String,
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    network: HttpNetworkSettings = HttpNetworkSettings.default
  ): EmbeddingProviderConfig =
    new EmbeddingProviderConfig(baseUrl, model, apiKey, timeouts, network)

  /** The signature before `network` was added, kept so code compiled against it still links. */
  def apply(baseUrl: String, model: String, apiKey: String, timeouts: ProviderTimeouts): EmbeddingProviderConfig =
    new EmbeddingProviderConfig(baseUrl, model, apiKey, timeouts, HttpNetworkSettings.default)
}
