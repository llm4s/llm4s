package org.llm4s.llmconnect.config

import org.llm4s.util.Redaction

/**
 * Configuration required to connect to an embedding provider endpoint.
 *
 * @param baseUrl base URL of the embedding service (e.g. `https://api.openai.com/v1`)
 * @param model   name of the embedding model to use
 * @param apiKey  authentication key for the provider; redacted in `toString`
 */
final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
) {
  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}
