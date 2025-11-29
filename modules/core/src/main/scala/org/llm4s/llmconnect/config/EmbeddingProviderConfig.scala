package org.llm4s.llmconnect.config

/**
 * Typed configuration for a text embeddings HTTP provider.
 *
 * This is a simple data holder; all configuration loading (env, system properties,
 * HOCON/PureConfig) is handled elsewhere (e.g. via org.llm4s.config.Llm4sConfig).
 */
final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)

