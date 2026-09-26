package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-openai-compatible`.
 *
 * These were `ConfigKeys.DEEPSEEK_*` in `llm4s-core` until the providers moved
 * to their own module ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a
 * key belongs with the code that reads it, so that `ConfigKeys` does not name
 * variables for a provider that may not be on the classpath.
 */
object OpenAICompatibleConfigKeys {
  // ---- DeepSeek -----------------------------------------------------------

  /** DeepSeek API key. */
  val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

  /** Overrides the DeepSeek API base URL. Defaults to `"https://api.deepseek.com"`. */
  val DEEPSEEK_BASE_URL = "DEEPSEEK_BASE_URL"
}
