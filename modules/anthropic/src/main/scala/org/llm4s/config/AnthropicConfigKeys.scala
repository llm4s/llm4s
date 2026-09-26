package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-anthropic`.
 *
 * These were `ConfigKeys.ANTHROPIC_*` in `llm4s-core` until the provider moved to
 * its own module ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]): a key
 * belongs with the code that reads it, so that `ConfigKeys` does not name
 * variables for a provider that may not be on the classpath.
 */
object AnthropicConfigKeys {

  /** Anthropic API key (`sk-ant-...`). */
  val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"

  /** Overrides the Anthropic API base URL. Defaults to `"https://api.anthropic.com"`. */
  val ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL"
}
