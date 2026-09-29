package org.llm4s.config

/**
 * Environment variables bound by `llm4s-agent-tools`' `reference.conf` under `llm4s.tools.*`.
 *
 * `BRAVE_SEARCH_API_KEY` was `ConfigKeys.BRAVE_SEARCH_API_KEY` in `llm4s-core`; it moved with the
 * search tools (#1242), as the Langfuse keys moved to `LangfuseConfigKeys`.
 */
object ToolsConfigKeys {

  /** Brave Search API key, bound to `llm4s.tools.brave.apiKey`. Required by the Brave web-search tool. */
  val BRAVE_SEARCH_API_KEY = "BRAVE_SEARCH_API_KEY"

  /** Exa Search API key, bound to `llm4s.tools.exa.apiKey`. Required by the Exa web-search tool. */
  val EXA_API_KEY = "EXA_API_KEY"
}
