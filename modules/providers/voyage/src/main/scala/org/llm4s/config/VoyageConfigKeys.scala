package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-voyage`.
 *
 * These were `ConfigKeys.VOYAGE_*` in `llm4s-core` until Voyage moved to its own module
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key belongs with the code that
 * reads it, so that `ConfigKeys` does not name variables for a provider that may not be on
 * the classpath. They are bound in this module's `reference.conf`: the key under the shared
 * `llm4s.credentials.voyage`, the base URL and model under `llm4s.embeddings.voyage`.
 */
object VoyageConfigKeys {

  /** Voyage AI API key (`pa-...`), the variable Voyage's own SDK reads. */
  val VOYAGE_API_KEY = "VOYAGE_API_KEY"

  /** Overrides the Voyage AI embedding base URL. Defaults to `"https://api.voyageai.com/v1"`. */
  val VOYAGE_EMBEDDING_BASE_URL = "VOYAGE_EMBEDDING_BASE_URL"

  /** Selects the Voyage AI embedding model when using the legacy provider format. */
  val VOYAGE_EMBEDDING_MODEL = "VOYAGE_EMBEDDING_MODEL"
}
