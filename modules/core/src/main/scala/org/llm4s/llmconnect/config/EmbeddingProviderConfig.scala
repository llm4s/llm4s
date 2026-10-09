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
 */
@Stable
final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String,
  timeouts: ProviderTimeouts = ProviderTimeouts.default
) {
  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}
