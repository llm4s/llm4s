package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-openai`: OpenAI, Requesty,
 * Azure OpenAI and OpenAI embeddings.
 *
 * These were `ConfigKeys.OPENAI_*`, `ConfigKeys.REQUESTY_BASE_URL` and
 * `ConfigKeys.AZURE_*` in `llm4s-core` until the providers moved to their own
 * module ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key belongs
 * with the code that reads it, so that `ConfigKeys` does not name variables for
 * a provider that may not be on the classpath. OpenRouter's alias is
 * `OpenAICompatibleConfigKeys.OPENROUTER_BASE_URL` in `llm4s-openai-compatible`.
 */
object OpenAIConfigKeys {
  // ---- OpenAI -------------------------------------------------------------

  /** OpenAI API key (`sk-...`). Also used for embeddings when no separate embedding key is set. */
  val OPENAI_API_KEY = "OPENAI_API_KEY"

  /**
   * Overrides the OpenAI API base URL.
   *
   * Defaults to `"https://api.openai.com/v1"`. Set to an OpenRouter URL
   * (containing `"openrouter.ai"`) to route through OpenRouter without a
   * separate config type - the same [[org.llm4s.llmconnect.config.OpenAIConfig]]
   * is reused and the client detects OpenRouter from this URL.
   */
  val OPENAI_BASE_URL = "OPENAI_BASE_URL"

  /** Optional OpenAI organisation ID forwarded in the `OpenAI-Organization` header. */
  val OPENAI_ORG = "OPENAI_ORGANIZATION"

  // ---- Requesty (OpenAI-compatible) ---------------------------------------

  /**
   * Requesty base URL alias.
   *
   * Requesty is an OpenAI-compatible gateway and uses the same `OPENAI_BASE_URL`
   * variable - there is no separate `REQUESTY_BASE_URL`. Point `OPENAI_BASE_URL`
   * at the Requesty router endpoint and configure a named provider with
   * `provider = "requesty"`.
   */
  val REQUESTY_BASE_URL = OPENAI_BASE_URL // alias via base URL

  // ---- Azure OpenAI -------------------------------------------------------

  /** Azure OpenAI deployment endpoint URL, e.g. `"https://my-resource.openai.azure.com/..."`. */
  val AZURE_API_BASE = "AZURE_API_BASE"

  /** Azure API key for the deployment. */
  val AZURE_API_KEY = "AZURE_API_KEY"

  /** Azure OpenAI API version string, e.g. `"2025-01-01-preview"`. */
  val AZURE_API_VERSION = "AZURE_API_VERSION"

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
}
