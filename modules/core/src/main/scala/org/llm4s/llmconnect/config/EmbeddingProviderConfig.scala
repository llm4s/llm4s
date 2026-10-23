package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
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
 *
 * Build one with [[EmbeddingProviderConfig.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingProviderConfig private (
  baseUrl: String,
  model: String,
  apiKey: String,
  timeouts: ProviderTimeouts
) {

  /** Sets the base URL of the embedding service. */
  def withBaseUrl(baseUrl: String): EmbeddingProviderConfig = copy(baseUrl = baseUrl)

  /** Sets the embedding model. */
  def withModel(model: String): EmbeddingProviderConfig = copy(model = model)

  /** Sets the authentication key. */
  def withApiKey(apiKey: String): EmbeddingProviderConfig = copy(apiKey = apiKey)

  /** Sets how long an embedding request may take. */
  def withTimeouts(timeouts: ProviderTimeouts): EmbeddingProviderConfig = copy(timeouts = timeouts)

  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}

object EmbeddingProviderConfig {

  /**
   * Creates an [[EmbeddingProviderConfig]]. `timeouts` defaults to [[ProviderTimeouts.default]], the provider's own
   * default.
   */
  def apply(
    baseUrl: String,
    model: String,
    apiKey: String,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
  ): EmbeddingProviderConfig =
    new EmbeddingProviderConfig(baseUrl, model, apiKey, timeouts)
}
