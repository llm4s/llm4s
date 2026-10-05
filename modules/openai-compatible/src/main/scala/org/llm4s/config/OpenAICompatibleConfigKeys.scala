package org.llm4s.config

import org.llm4s.annotation.Stable

/**
 * Environment-variable names recognised by `llm4s-openai-compatible`.
 *
 * These were `ConfigKeys.OPENROUTER_BASE_URL`, `ConfigKeys.DEEPSEEK_*` and `ConfigKeys.MISTRAL_*` in
 * `llm4s-core` until the providers moved to their own module
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key belongs with the
 * code that reads it, so that `ConfigKeys` does not name variables for a
 * provider that may not be on the classpath.
 */
@Stable
object OpenAICompatibleConfigKeys {
  // ---- OpenRouter ---------------------------------------------------------

  /**
   * OpenRouter base URL alias.
   *
   * OpenRouter uses the `OPENAI_BASE_URL` variable - there is no separate
   * `OPENROUTER_BASE_URL`. Point `OPENAI_BASE_URL` at your OpenRouter endpoint
   * and configure a named provider with `provider = "openrouter"`.
   */
  val OPENROUTER_BASE_URL = "OPENAI_BASE_URL" // alias via base URL

  /** OpenRouter API key, as OpenRouter's SDK reads it. Bound to `llm4s.credentials.openrouter.apiKey`. */
  val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"

  // ---- DeepSeek -----------------------------------------------------------

  /** DeepSeek API key, as DeepSeek's API docs name it. Bound to `llm4s.credentials.deepseek.apiKey`. */
  val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

  /** Overrides the DeepSeek API base URL. Defaults to `"https://api.deepseek.com"`. */
  val DEEPSEEK_BASE_URL = "DEEPSEEK_BASE_URL"

  // ---- Mistral ------------------------------------------------------------

  /**
   * Mistral API key, as Mistral's SDK reads it. Bound to `llm4s.credentials.mistral.apiKey`.
   * This was `ConfigKeys.MISTRAL_API_KEY`.
   */
  val MISTRAL_API_KEY = "MISTRAL_API_KEY"

  /** Overrides the Mistral API root. Defaults to `"https://api.mistral.ai"`. This was `ConfigKeys.MISTRAL_BASE_URL`. */
  val MISTRAL_BASE_URL = "MISTRAL_BASE_URL"

  // ---- Z.ai ---------------------------------------------------------------

  /** Z.ai API key, as Z.ai's SDK (`zai-sdk`) reads it. Bound to `llm4s.credentials.zai.apiKey`. */
  val ZAI_API_KEY = "ZAI_API_KEY"

  // ---- Cohere -------------------------------------------------------------

  /**
   * Cohere API key. Bound to `llm4s.credentials.cohere.apiKey`, which the Cohere chat dialect and
   * `llm4s-rag`'s Cohere reranker share. Cohere's Python SDK prefers `CO_API_KEY` and also accepts
   * this one; llm4s binds this one only, the name it has always used for Cohere.
   */
  val COHERE_API_KEY = "COHERE_API_KEY"

  // ---- Generic openai-compatible -----------------------------------------

  /**
   * Base URL of a generic OpenAI-compatible endpoint, for `LLM_MODEL=openai-compatible/<model>`
   * wherever that shorthand is read - the chat-tui sample and the config-policy env check.
   * Required there: the generic provider has no default endpoint.
   *
   * `Llm4sConfig` does not read it: the generic provider has no vendor, so nothing binds its
   * variables. A named section binds its own, e.g. `baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}`.
   */
  val OPENAI_COMPATIBLE_BASE_URL = "OPENAI_COMPATIBLE_BASE_URL"

  /**
   * API key for a generic OpenAI-compatible endpoint, alongside [[OPENAI_COMPATIBLE_BASE_URL]].
   * Optional: with none, no `Authorization` header is sent.
   */
  val OPENAI_COMPATIBLE_API_KEY = "OPENAI_COMPATIBLE_API_KEY"
}
