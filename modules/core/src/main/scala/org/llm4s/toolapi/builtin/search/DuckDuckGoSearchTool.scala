package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import scala.util.control.NonFatal

import org.llm4s.config.DuckDuckGoSearchToolConfig
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.util.Redaction

/**
 * A related topic from web search.
 */
case class RelatedTopic(
  text: String,
  url: Option[String]
)

object RelatedTopic {
  implicit val relatedTopicRW: ReadWriter[RelatedTopic] = macroRW[RelatedTopic]
}

/**
 * DuckDuckGo search result.
 */
case class DuckDuckGoSearchResult(
  query: String,
  abstract_ : String,
  abstractSource: String,
  abstractUrl: String,
  answer: String,
  answerType: String,
  relatedTopics: Seq[RelatedTopic],
)

object DuckDuckGoSearchResult {
  implicit val duckDuckGoSearchResultRW: ReadWriter[DuckDuckGoSearchResult] = macroRW[DuckDuckGoSearchResult]
}

/**
 * Configuration for DuckDuckGo search tool.
 *
 * @param timeoutMs Request timeout in milliseconds.
 * @param maxResults Maximum number of related topics to return.
 * @param safeSearch Whether to enable safe search.
 */
case class DuckDuckGoSearchConfig(
  timeoutMs: Int = 10000,
  maxResults: Int = 10,
  safeSearch: Boolean = true
)

/**
 * Tool for web searching using DuckDuckGo's Instant Answer API.
 *
 * This tool provides quick answers and definitions without requiring an API key.
 * It's best suited for factual queries, definitions, and quick lookups.
 *
 * Note: This uses DuckDuckGo's free Instant Answer API which provides:
 * - Definitions from Wikipedia
 * - Quick facts
 * - Related topics
 * - Disambiguation pages
 *
 * It does NOT provide full web search results (that would require a paid API).
 *
 * Architecture:
 * This tool follows the "config at the edge" pattern:
 * 1. Configuration is loaded at the application boundary via Llm4sConfig.loadDuckDuckGoSearchTool()
 * 2. The loaded DuckDuckGoSearchToolConfig is passed to create() method
 * 3. The tool operates with the provided configuration
 *
 * This keeps the tool implementation pure and testable without direct config dependencies.
 *
 * @example
 * {{{
 * import org.llm4s.config.Llm4sConfig
 * import org.llm4s.toolapi.builtin.search._
 *
 * // Load configuration at the application edge
 * val toolConfigResult = Llm4sConfig.loadDuckDuckGoSearchTool()
 *
 * toolConfigResult match {
 *   case Right(toolConfig) =>
 *     // Create the tool with loaded configuration
 *     val searchTool = DuckDuckGoSearchTool.create(toolConfig)
 *     val tools = new ToolRegistry(Seq(searchTool))
 *     agent.run("What is Scala programming language?", tools)
 *
 *   case Left(error) =>
 *     println(s"Failed to load DuckDuckGo config: $error")
 * }
 * }}}
 *
 * For testing, you can create a config directly:
 * {{{
 * import org.llm4s.config.DuckDuckGoSearchToolConfig
 *
 * val testConfig = DuckDuckGoSearchToolConfig(apiUrl = "https://api.duckduckgo.com")
 * val searchTool = DuckDuckGoSearchTool.create(testConfig)
 * }}}
 */
object DuckDuckGoSearchTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("DuckDuckGo search parameters")
    .withProperty(
      Schema.property(
        "search_query",
        Schema.string("The search query (best for definitions, facts, quick lookups)")
      )
    )

  /**
   * Create a DuckDuckGo search tool with the given configuration.
   *
   * This method follows the "config at the edge" pattern where configuration is loaded
   * at the application boundary and passed in as a parameter.
   *
   * @param toolConfig The tool configuration containing API URL (loaded via Llm4sConfig.loadDuckDuckGoSearchTool())
   * @param config Optional runtime configuration for timeout, maxResults, and safeSearch settings
   * @return A configured ToolFunction ready to be registered with the agent
   *
   * @example
   * {{{
   * // Load config at application edge
   * val toolConfig = Llm4sConfig.loadDuckDuckGoSearchTool().getOrElse(
   *   throw new RuntimeException("Failed to load DuckDuckGo config")
   * )
   *
   * // Create tool with custom runtime settings
   * val searchTool = DuckDuckGoSearchTool.create(
   *   toolConfig = toolConfig,
   *   config = DuckDuckGoSearchConfig(
   *     timeoutMs = 5000,
   *     maxResults = 5,
   *     safeSearch = true
   *   )
   * )
   * }}}
   */
  def create(
    toolConfig: DuckDuckGoSearchToolConfig,
    config: DuckDuckGoSearchConfig = DuckDuckGoSearchConfig(),
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
    restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
  ): Result[ToolFunction[Map[String, Any], DuckDuckGoSearchResult]] =
    ToolBuilder[Map[String, Any], DuckDuckGoSearchResult](
      name = "duckduckgo_search",
      description = "Search the web for definitions, facts, and quick answers using DuckDuckGo. " +
        "Best for factual queries and definitions. Does not provide full web search results.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        searchQuery <- extractor.getString("search_query")
        _           <- if (searchQuery.trim.isEmpty) Left("search_query cannot be empty") else Right(())
        result      <- search(toolConfig.apiUrl, searchQuery, config, httpClient, restoreInterrupt)
      } yield result
    }.buildSafe()

  private val SAFE_SEARCH   = "1"
  private val UNSAFE_SEARCH = "-1"
  private[search] def search(
    apiUrl: String,
    query: String,
    config: DuckDuckGoSearchConfig,
    httpClient: Llm4sHttpClient,
    restoreInterrupt: () => Unit
  ): Either[String, DuckDuckGoSearchResult] = {

    val safeSearch = if (config.safeSearch) SAFE_SEARCH else UNSAFE_SEARCH
    val params = Map(
      "q"             -> query,
      "format"        -> "json",
      "no_html"       -> "1",
      "skip_disambig" -> "0",
      "t"             -> "llm4s",
      "safesearch"    -> safeSearch
    )

    // Catch only non-fatal exceptions. Fatal errors (OOM, StackOverflow, etc.) will crash fast.
    // InterruptedException is handled explicitly to restore the interrupt flag.
    val responseEither: Either[String, HttpResponse] =
      try
        Right(
          httpClient.get(
            url = apiUrl,
            headers = Map(
              "User-Agent" -> "llm4s-duckduckgo-search/1.0"
            ),
            params = params,
            timeout = config.timeoutMs
          )
        )
      catch {
        case _: InterruptedException =>
          // Restore interrupt flag for proper thread shutdown and timeout semantics
          restoreInterrupt()
          Left("Search request was cancelled or interrupted.")
        case _: java.net.http.HttpTimeoutException =>
          Left(s"Search request timed out after ${config.timeoutMs}ms. Please try again with a simpler query.")
        case _: java.net.UnknownHostException =>
          Left("Unable to reach search service. Please check network connectivity.")
        case _: java.net.ConnectException =>
          Left("Failed to connect to search service. The service may be temporarily unavailable.")
        case NonFatal(_) =>
          // Catch all other non-fatal exceptions (IOException, etc.)
          // Fatal errors (OutOfMemoryError, StackOverflowError, etc.) will propagate
          Left("Search request failed due to a network error. Please try again.")
      }

    responseEither.flatMap { response =>
      if (response.statusCode == 200) {
        // Parse successful response, catching only non-fatal exceptions
        try {
          val json = ujson.read(response.body)
          Right(parseResults(query, json, config))
        } catch {
          case _: InterruptedException =>
            // Restore interrupt flag for proper thread shutdown and timeout semantics
            restoreInterrupt()
            Left("Response parsing was cancelled or interrupted.")
          case _: ujson.ParseException =>
            Left("Failed to parse search results. The response format may be invalid.")
          case NonFatal(_) =>
            // Catch all other non-fatal exceptions
            // Fatal errors (OutOfMemoryError, StackOverflowError, etc.) will propagate
            Left("Failed to process search results. Please try again.")
        }
      } else {
        val body = Redaction.redactForLogging(response.body)
        Left(s"DuckDuckGo search returned status ${response.statusCode}: $body")
      }
    }
  }

  /**
   * Parses the JSON response from DuckDuckGo API into a structured result.
   *
   * This function safely extracts data from the DuckDuckGo API response using
   * `strOpt` and `arrOpt` to handle null values and type mismatches gracefully.
   *
   * @param query The original search query
   * @param json The parsed JSON response from DuckDuckGo API
   * @param config Configuration containing maxResults and other settings
   * @return A structured DuckDuckGoSearchResult containing:
   *         - Abstract: Summary from Wikipedia or other sources
   *         - Answer: Direct answer if available
   *         - RelatedTopics: List of related topics (limited by maxResults)
   */
  private[search] def parseResults(
    query: String,
    json: ujson.Value,
    config: DuckDuckGoSearchConfig
  ): DuckDuckGoSearchResult = {
    val relatedTopics = json.obj
      .get("RelatedTopics")
      .flatMap(_.arrOpt)
      .map { topics =>
        topics
          .take(config.maxResults)
          .flatMap { topic =>
            topic.obj.get("Text").flatMap(_.strOpt).map { text =>
              RelatedTopic(
                text = text,
                url = topic.obj.get("FirstURL").flatMap(_.strOpt)
              )
            }
          }
          .toSeq
      }
      .getOrElse(Seq.empty)

    DuckDuckGoSearchResult(
      query = query,
      abstract_ = json.obj.get("Abstract").flatMap(_.strOpt).getOrElse(""),
      abstractSource = json.obj.get("AbstractSource").flatMap(_.strOpt).getOrElse(""),
      abstractUrl = json.obj.get("AbstractURL").flatMap(_.strOpt).getOrElse(""),
      answer = json.obj.get("Answer").flatMap(_.strOpt).getOrElse(""),
      answerType = json.obj.get("AnswerType").flatMap(_.strOpt).getOrElse(""),
      relatedTopics = relatedTopics,
    )
  }

}
