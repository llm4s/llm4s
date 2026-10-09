package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-jina`.
 *
 * A key belongs with the code that reads it, so these live here and not in `ConfigKeys`, which
 * must not name variables for a provider that may not be on the classpath. They are bound in
 * this module's `reference.conf`: the key under the shared `llm4s.credentials.jina`, the base
 * URL and model under `llm4s.embeddings.jina`.
 */
object JinaConfigKeys {

  /** Jina AI API key. */
  val JINA_API_KEY = "JINA_API_KEY"

  /** Overrides the Jina AI embedding base URL. Defaults to `"https://api.jina.ai/v1"`. */
  val JINA_EMBEDDING_BASE_URL = "JINA_EMBEDDING_BASE_URL"

  /** Selects the Jina AI embedding model when using the legacy provider format. */
  val JINA_EMBEDDING_MODEL = "JINA_EMBEDDING_MODEL"
}
