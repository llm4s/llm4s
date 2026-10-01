package org.llm4s.toolapi.builtin

/**
 * Search tools for web searches and lookups.
 *
 * These tools provide web search capabilities using various search engines.
 * All search tools follow the "config at the edge" pattern where configuration
 * is loaded at the application boundary via [[org.llm4s.config.ToolsConfigLoader]]
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
 * - [[ExaSearchTool]]: Search using Exa (formerly Metaphor) API
 *   - AI-powered semantic search engine
 *   - Requires API key (paid service)
 *   - Supports multiple search types: auto, neural, fast, deep
 *   - Returns rich content with text, highlights, summaries, and metadata
 *   - Configuration: API key, URL, result count, search type, max characters, max age
 *
 * == Usage Pattern ==
 *
 * All search tools follow the Result-based error handling pattern.
 * Use for-comprehensions to chain operations and handle errors gracefully:
 *
 * @example
 * {{{
 * import org.llm4s.config.ToolsConfigLoader
 * import org.llm4s.toolapi.builtin.search._
 * import org.llm4s.toolapi.ToolRegistry
 * import org.llm4s.types.Result
 *
 * // Load and create all search tools using Result
 * val toolsResult: Result[ToolRegistry] = for {
 *   // Load DuckDuckGo configuration and create tool
 *   duckDuckGoConfig <- ToolsConfigLoader.loadDuckDuckGoSearchTool()
 *   duckDuckGoTool   <- DuckDuckGoSearchTool.create(duckDuckGoConfig)
 *
 *   // Load Brave Search configuration and create tool
 *   braveConfig <- ToolsConfigLoader.loadBraveSearchTool()
 *   braveTool   <- BraveSearchTool.create(braveConfig)
 *
 *   // Load Exa Search configuration and create tool
 *   exaConfig <- ToolsConfigLoader.loadExaSearchTool()
 *   exaTool   <- ExaSearchTool.create(exaConfig)
 *
 *   // Register all tools
 *   tools = new ToolRegistry(Seq(duckDuckGoTool, braveTool, exaTool))
 * } yield tools
 *
 * // Handle the result
 * toolsResult match {
 *   case Right(tools) =>
 *     // Use tools with agent
 *     println(s"Successfully loaded $${tools.tools.size} search tools")
 *   case Left(error) =>
 *     // Handle configuration or validation errors
 *     println(s"Failed to load search tools: $${error.message}")
 * }
 * }}}
 *
 * == Individual Tool Usage ==
 *
 * You can also load and use tools individually:
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.search.ExaSearchTool
 *
 * // Load just Exa Search
 * val exaToolResult = for {
 *   config <- ToolsConfigLoader.loadExaSearchTool()
 *   tool   <- ExaSearchTool.create(config)
 * } yield tool
 *
 * exaToolResult match {
 *   case Right(tool) => // Use tool
 *   case Left(error) => // Handle error
 * }
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
 *     exa {
 *       apiKey = "your-exa-api-key"
 *       apiUrl = "https://api.exa.ai"
 *       numResults = 10
 *       searchType = "auto"        # Options: auto, neural, fast, deep
 *       maxCharacters = 500
 *     }
 *   }
 * }
 * }}}
 */
package object search {

  /**
   * Detects whether a thrown exception represents malformed JSON.
   *
   * upickle/ujson 4.4 routes reads through a tracing visitor, so a parse failure surfaces
   * as `upickle.core.TraceVisitor$TraceException` with the original `ujson.ParseException`
   * (or `ujson.IncompleteParseException`) as its cause rather than being thrown directly.
   * Walking the cause chain for the shared `ujson.ParsingFailedException` interface keeps
   * malformed-JSON detection working regardless of how the failure is wrapped.
   */
  private[search] def isJsonParseFailure(t: Throwable): Boolean =
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(_ != null)
      .exists(_.isInstanceOf[ujson.ParsingFailedException])

  /**
   * The user-facing message for a request the HTTP client could not complete. The client has
   * already restored the thread's interrupt flag for an interruption.
   */
  private[search] def describeTransportFailure(
    error: org.llm4s.error.LLMError,
    timeout: scala.concurrent.duration.FiniteDuration
  ): String = {
    def causedBy[T <: Throwable](cause: Option[Throwable])(using ct: scala.reflect.ClassTag[T]): Boolean =
      cause.exists(c => Iterator.iterate(c)(_.getCause).takeWhile(_ != null).exists(ct.runtimeClass.isInstance))
    error match {
      case _: org.llm4s.error.ExecutionError =>
        "Search request was cancelled or interrupted."
      case _: org.llm4s.error.TimeoutError =>
        s"Search request timed out after ${timeout.toMillis}ms. Please try again with a simpler query."
      case e: org.llm4s.error.NetworkError if causedBy[java.net.UnknownHostException](e.cause) =>
        "Unable to reach search service. Please check network connectivity."
      case e: org.llm4s.error.NetworkError if causedBy[java.net.ConnectException](e.cause) =>
        "Failed to connect to search service. The service may be temporarily unavailable."
      case _ =>
        "Search request failed due to a network error. Please try again."
    }
  }
}
