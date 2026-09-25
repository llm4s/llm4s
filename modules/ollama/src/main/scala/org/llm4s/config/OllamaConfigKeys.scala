package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-ollama`.
 *
 * These were `ConfigKeys.OLLAMA_*` in `llm4s-core` until the provider moved to
 * its own module ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key
 * belongs with the code that reads it, so that `ConfigKeys` does not name
 * variables for a provider that may not be on the classpath.
 */
object OllamaConfigKeys {

  /**
   * Conventional Ollama server URL variable, named in the "missing baseUrl"
   * error for a named `ollama` provider. Chat config is keyed by the instance
   * name, so it is bound in your own HOCON, e.g.
   * `llm4s.providers.ollama-local.baseUrl = ${?OLLAMA_BASE_URL}`.
   */
  val OLLAMA_BASE_URL = "OLLAMA_BASE_URL"

  /** Overrides the Ollama embedding base URL; bound to `llm4s.embeddings.ollama.baseUrl`. */
  val OLLAMA_EMBEDDING_BASE_URL = "OLLAMA_EMBEDDING_BASE_URL"

  /** Selects the Ollama embedding model; bound to `llm4s.embeddings.ollama.model`. */
  val OLLAMA_EMBEDDING_MODEL = "OLLAMA_EMBEDDING_MODEL"
}
