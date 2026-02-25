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
 *  - `LLM_MODEL` — required; format `provider/model` (e.g. `"openai/gpt-4o"`).
 *  - Provider API keys — required for cloud providers; see per-section comments.
 *  - `TRACING_MODE` — optional; `langfuse`, `opentelemetry`, `console`, or `none`.
 *  - `EMBEDDING_MODEL` — required when using embeddings; format `provider/model`.
 */
object ConfigKeys {
  // ---- Core model selection -----------------------------------------------

  /** Selects the LLM provider and model. Format: `provider/model`, e.g. `"openai/gpt-4o"`. */
  val LLM_MODEL = "LLM_MODEL"

  // ---- OpenAI -------------------------------------------------------------

  /** OpenAI API key (`sk-...`). Also used for embeddings when no separate embedding key is set. */
  val OPENAI_API_KEY = "OPENAI_API_KEY"

  /**
   * Overrides the OpenAI API base URL.
   *
   * Defaults to `"https://api.openai.com/v1"`. Set to an OpenRouter URL
   * (containing `"openrouter.ai"`) to route through OpenRouter without a
   * separate config type — the same [[org.llm4s.llmconnect.config.OpenAIConfig]]
   * is reused and the client detects OpenRouter from this URL.
   */
  val OPENAI_BASE_URL = "OPENAI_BASE_URL"

  /** Optional OpenAI organisation ID forwarded in the `OpenAI-Organization` header. */
  val OPENAI_ORG = "OPENAI_ORGANIZATION"

  // ---- OpenRouter (OpenAI-compatible) -------------------------------------

  /**
   * OpenRouter base URL alias.
   *
   * OpenRouter uses the same `OPENAI_BASE_URL` variable — there is no separate
   * `OPENROUTER_BASE_URL`. Set `OPENAI_BASE_URL` to your OpenRouter endpoint
   * and `LLM_MODEL` to `openrouter/model-name`.
   */
  val OPENROUTER_BASE_URL = OPENAI_BASE_URL // alias via base URL

  // ---- Azure OpenAI -------------------------------------------------------

  /** Azure OpenAI deployment endpoint URL, e.g. `"https://my-resource.openai.azure.com/..."`. */
  val AZURE_API_BASE = "AZURE_API_BASE"

  /** Azure API key for the deployment. */
  val AZURE_API_KEY = "AZURE_API_KEY"

  /** Azure OpenAI API version string, e.g. `"2025-01-01-preview"`. */
  val AZURE_API_VERSION = "AZURE_API_VERSION"

  // ---- Anthropic ----------------------------------------------------------

  /** Anthropic API key (`sk-ant-...`). */
  val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"

  /** Overrides the Anthropic API base URL. Defaults to `"https://api.anthropic.com"`. */
  val ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL"

  // ---- Ollama (local) -----------------------------------------------------

  /** Ollama server URL. Defaults to `"http://localhost:11434"` when not set. */
  val OLLAMA_BASE_URL = "OLLAMA_BASE_URL"

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

  // ---- Embeddings: OpenAI -------------------------------------------------

  /**
   * Overrides the base URL used for OpenAI embedding requests.
   *
   * Useful when routing embeddings through a proxy or compatible endpoint
   * independently of the LLM base URL.
   */
  val OPENAI_EMBEDDING_BASE_URL = "OPENAI_EMBEDDING_BASE_URL"

  /** Selects the OpenAI embedding model when using the legacy provider format. */
  val OPENAI_EMBEDDING_MODEL = "OPENAI_EMBEDDING_MODEL"

  // ---- Embeddings: Voyage AI ----------------------------------------------

  /** Voyage AI API key (`pa-...`). */
  val VOYAGE_API_KEY = "VOYAGE_API_KEY"

  /** Overrides the Voyage AI embedding base URL. */
  val VOYAGE_EMBEDDING_BASE_URL = "VOYAGE_EMBEDDING_BASE_URL"

  /** Selects the Voyage AI embedding model when using the legacy provider format. */
  val VOYAGE_EMBEDDING_MODEL = "VOYAGE_EMBEDDING_MODEL"

  // ---- Embeddings: Ollama (local) -----------------------------------------

  /** Overrides the Ollama embedding base URL independently of [[OLLAMA_BASE_URL]]. */
  val OLLAMA_EMBEDDING_BASE_URL = "OLLAMA_EMBEDDING_BASE_URL"

  /** Selects the Ollama embedding model when using the legacy provider format. */
  val OLLAMA_EMBEDDING_MODEL = "OLLAMA_EMBEDDING_MODEL"

  // ---- Embeddings: chunking -----------------------------------------------

  /** Token count per chunk when splitting documents for embedding. Default: `1000`. */
  val CHUNK_SIZE = "CHUNK_SIZE"

  /** Token overlap between consecutive chunks. Default: `100`. */
  val CHUNK_OVERLAP = "CHUNK_OVERLAP"

  /** Enables or disables document chunking (`true`/`false`). Default: `true`. */
  val CHUNKING_ENABLED = "CHUNKING_ENABLED"

  // ---- Tool API keys ------------------------------------------------------

  /** Brave Search API key. Required when using the Brave web-search tool. */
  val BRAVE_SEARCH_API_KEY = "BRAVE_SEARCH_API_KEY"
}
