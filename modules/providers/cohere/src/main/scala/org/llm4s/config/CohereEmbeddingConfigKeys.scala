package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-cohere`.
 *
 * A key belongs with the code that reads it, so these live here and not in `ConfigKeys`, which
 * must not name variables for a provider that may not be on the classpath. They are bound in
 * this module's `reference.conf`: the key under the shared `llm4s.credentials.cohere`, the base
 * URL and model under `llm4s.embeddings.cohere`.
 *
 * `COHERE_API_KEY` is also bound by `llm4s-openai-compatible` (Cohere chat) and `llm4s-rag` (the
 * Cohere reranker), so one key serves all three; it is bound here too so that `llm4s-cohere`
 * works without either.
 */
object CohereEmbeddingConfigKeys {

  /** Cohere API key. */
  val COHERE_API_KEY = "COHERE_API_KEY"

  /** Overrides the Cohere embedding base URL. Defaults to `"https://api.cohere.com"`. */
  val COHERE_EMBEDDING_BASE_URL = "COHERE_EMBEDDING_BASE_URL"

  /** Selects the Cohere embedding model when using the legacy provider format. */
  val COHERE_EMBEDDING_MODEL = "COHERE_EMBEDDING_MODEL"
}
