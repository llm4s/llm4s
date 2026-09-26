package org.llm4s.config

/**
 * Canonical environment-variable names recognised by [[Llm4sConfig]].
 *
 * All LLM4S configuration is read from environment variables (or JVM system
 * properties with the same name). This object centralises the names to avoid
 * typos and make grep-friendly references possible.
 *
 * == Quick reference ==
 *
 *  - Provider API keys — required for cloud providers; see per-section comments.
 *  - `TRACING_MODE` — optional; `langfuse`, `opentelemetry`, `console`, or `none`.
 *  - `EMBEDDING_MODEL` — required when using embeddings; format `provider/model`.
 */
object ConfigKeys {
  // OpenAI, Requesty and Azure OpenAI keys are `OpenAIConfigKeys` in `llm4s-openai` (#1132).

  // ---- OpenRouter (OpenAI-compatible) -------------------------------------

  /**
   * OpenRouter base URL alias.
   *
   * OpenRouter uses the `OPENAI_BASE_URL` variable - there is no separate
   * `OPENROUTER_BASE_URL`. Point `OPENAI_BASE_URL` at your OpenRouter endpoint
   * and configure a named provider with `provider = "openrouter"`.
   */
  val OPENROUTER_BASE_URL = "OPENAI_BASE_URL" // alias via base URL

  // ---- DeepSeek -----------------------------------------------------------

  /** DeepSeek API key. */
  val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

  /** Overrides the DeepSeek API base URL. Defaults to `"https://api.deepseek.com"`. */
  val DEEPSEEK_BASE_URL = "DEEPSEEK_BASE_URL"

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

  // ---- Embeddings: Voyage AI ----------------------------------------------

  /** Voyage AI API key (`pa-...`). */
  val VOYAGE_API_KEY = "VOYAGE_API_KEY"

  /** Overrides the Voyage AI embedding base URL. */
  val VOYAGE_EMBEDDING_BASE_URL = "VOYAGE_EMBEDDING_BASE_URL"

  /** Selects the Voyage AI embedding model when using the legacy provider format. */
  val VOYAGE_EMBEDDING_MODEL = "VOYAGE_EMBEDDING_MODEL"

  // ---- Embeddings: chunking -----------------------------------------------

  /** Token count per chunk when splitting documents for embedding. Default: `1000`. */
  val CHUNK_SIZE = "CHUNK_SIZE"

  /** Token overlap between consecutive chunks. Default: `100`. */
  val CHUNK_OVERLAP = "CHUNK_OVERLAP"

  /** Enables or disables document chunking (`true`/`false`). Default: `true`. */
  val CHUNKING_ENABLED = "CHUNKING_ENABLED"

  // Mistral
  val MISTRAL_API_KEY  = "MISTRAL_API_KEY"
  val MISTRAL_BASE_URL = "MISTRAL_BASE_URL"

  // Tool API Keys
  // ---- Tool API keys ------------------------------------------------------

  /** Brave Search API key. Required when using the Brave web-search tool. */
  val BRAVE_SEARCH_API_KEY = "BRAVE_SEARCH_API_KEY"
}
