package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-openai-compatible`.
 *
 * These were `ConfigKeys.OPENROUTER_BASE_URL`, `ConfigKeys.DEEPSEEK_*` and `ConfigKeys.MISTRAL_*` in
 * `llm4s-core` until the providers moved to their own module
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key belongs with the
 * code that reads it, so that `ConfigKeys` does not name variables for a
 * provider that may not be on the classpath.
 */
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

  // ---- DeepSeek -----------------------------------------------------------

  /** DeepSeek API key. */
  val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

  /** Overrides the DeepSeek API base URL. Defaults to `"https://api.deepseek.com"`. */
  val DEEPSEEK_BASE_URL = "DEEPSEEK_BASE_URL"

  // ---- Mistral ------------------------------------------------------------

  /** Mistral API key. This was `ConfigKeys.MISTRAL_API_KEY`. */
  val MISTRAL_API_KEY = "MISTRAL_API_KEY"

  /** Overrides the Mistral API root. Defaults to `"https://api.mistral.ai"`. This was `ConfigKeys.MISTRAL_BASE_URL`. */
  val MISTRAL_BASE_URL = "MISTRAL_BASE_URL"
}
