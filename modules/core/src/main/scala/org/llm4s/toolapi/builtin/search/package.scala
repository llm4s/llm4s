package org.llm4s.toolapi.builtin

/**
 * Search tools for web searches and lookups.
 *
 * These tools provide web search capabilities using various search engines.
 * All search tools follow the "config at the edge" pattern where configuration
 * is loaded at the application boundary via [[org.llm4s.config.Llm4sConfig]]
 * and passed to the tool's create() method.
 *
 * == Available Tools ==
 *
 * - [[DuckDuckGoSearchTool]]: Search using DuckDuckGo Instant Answer API
 *   - Best for definitions, facts, quick lookups
 *   - No API key required
 *   - Returns abstracts, related topics, and infobox data
 *   - Configuration: API URL
 *
 * - [[BraveSearchTool]]: Search using Brave Search API
 *   - Comprehensive web search results
 *   - Requires API key (paid service)
 *   - Returns web pages, snippets, and metadata
 *   - Configuration: API key, URL, result count, safe search settings
 *
 * == Usage Pattern ==
 *
 * All search tools require configuration to be loaded at the application edge:
 *
 * @example
 * {{{
 * import org.llm4s.config.Llm4sConfig
 * import org.llm4s.toolapi.builtin.search._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Load DuckDuckGo configuration
 * val duckDuckGoConfig = Llm4sConfig.loadDuckDuckGoSearchTool().getOrElse(
 *   throw new RuntimeException("Failed to load DuckDuckGo config")
 * )
 * val duckDuckGoTool = DuckDuckGoSearchTool.create(duckDuckGoConfig)
 *
 * // Load Brave Search configuration
 * val braveConfig = Llm4sConfig.loadBraveSearchTool().getOrElse(
 *   throw new RuntimeException("Failed to load Brave Search config")
 * )
 * val braveTool = BraveSearchTool.create(braveConfig)
 *
 * // Register tools with the agent
 * val tools = new ToolRegistry(Seq(duckDuckGoTool, braveTool))
 * }}}
 *
 * == Configuration ==
 *
 * Configure search tools in your application.conf:
 *
 * {{{
 * llm4s {
 *   tools {
 *     duckduckgo {
 *       apiUrl = "https://api.duckduckgo.com"
 *     }
 *     brave {
 *       apiKey = "your-api-key"
 *       apiUrl = "https://api.search.brave.com/res/v1"
 *       count = 10
 *       safeSearch = "moderate"
 *     }
 *   }
 * }
 * }}}
 */
package object search {}
