package org.llm4s.config

/**
 * Canonical environment-variable names bound by `llm4s-core`'s `reference.conf`.
 *
 * Each is read through a `${?VAR}` substitution under the `llm4s` config tree, so
 * the config key it feeds can also be set directly in `application.conf` or as a
 * JVM system property. This object centralises the names to avoid typos and make
 * grep-friendly references possible.
 *
 * == Quick reference ==
 *
 *  - Chat providers bind no variable: a named section under `llm4s.providers`
 *    binds its own, e.g. `apiKey = ${?OPENAI_API_KEY}`. Nothing reads `LLM_MODEL`.
 *  - `TRACING_MODE` — optional; `langfuse`, `opentelemetry`, `console`, or `none`.
 *  - `EMBEDDING_MODEL` — required when using embeddings; format `provider/model`.
 */
object ConfigKeys {
  // OpenAI, Requesty and Azure OpenAI keys are `OpenAIConfigKeys` in `llm4s-openai`, and the
  // OpenRouter, DeepSeek and Mistral keys `OpenAICompatibleConfigKeys` in
  // `llm4s-openai-compatible`, and the Voyage keys `VoyageConfigKeys` in `llm4s-voyage` (#1132).

  // ---- Langfuse tracing ---------------------------------------------------

  /** Langfuse server URL. Defaults to `"https://cloud.langfuse.com"` when not set. */
  val LANGFUSE_URL = "LANGFUSE_URL"

  /** Langfuse public key (`pk-lf-...`). */
  val LANGFUSE_PUBLIC_KEY = "LANGFUSE_PUBLIC_KEY"

  /** Langfuse secret key (`sk-lf-...`). */
  val LANGFUSE_SECRET_KEY = "LANGFUSE_SECRET_KEY"

  /** Optional Langfuse environment tag (e.g. `"production"`, `"staging"`). */
  val LANGFUSE_ENV = "LANGFUSE_ENV"

  /** Optional Langfuse release version tag. */
  val LANGFUSE_RELEASE = "LANGFUSE_RELEASE"

  /** Optional Langfuse SDK version override. */
  val LANGFUSE_VERSION = "LANGFUSE_VERSION"

  // ---- Embeddings: provider selection -------------------------------------

  /**
   * Unified embedding provider and model selector.
   *
   * Format: `provider/model`, e.g. `"openai/text-embedding-3-small"`,
   * `"voyage/voyage-3"`, `"ollama/nomic-embed-text"`. Takes precedence over
   * the legacy [[EMBEDDING_PROVIDER]] variable.
   */
  val EMBEDDING_MODEL = "EMBEDDING_MODEL"

  /** Legacy embedding provider selector; superseded by [[EMBEDDING_MODEL]]. */
  val EMBEDDING_PROVIDER = "EMBEDDING_PROVIDER"

  /** Path to the file or directory to embed. */
  val EMBEDDING_INPUT_PATH = "EMBEDDING_INPUT_PATH"

  /** Query string used when searching an embedding index. */
  val EMBEDDING_QUERY = "EMBEDDING_QUERY"

  // ---- Embeddings: chunking -----------------------------------------------

  /** Token count per chunk when splitting documents for embedding. Default: `1000`. */
  val CHUNK_SIZE = "CHUNK_SIZE"

  /** Token overlap between consecutive chunks. Default: `100`. */
  val CHUNK_OVERLAP = "CHUNK_OVERLAP"

  /** Enables or disables document chunking (`true`/`false`). Default: `true`. */
  val CHUNKING_ENABLED = "CHUNKING_ENABLED"

  // Tool API Keys
  // ---- Tool API keys ------------------------------------------------------

  /** Brave Search API key. Required when using the Brave web-search tool. */
  val BRAVE_SEARCH_API_KEY = "BRAVE_SEARCH_API_KEY"
}
