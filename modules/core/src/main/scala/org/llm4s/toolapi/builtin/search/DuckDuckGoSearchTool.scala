package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import upickle.default._

import java.net.URLEncoder
import scala.util.Try
import requests.Response

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
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.search._
 *
 * val searchTool = DuckDuckGoSearchTool.create()
 *
 * val tools = new ToolRegistry(Seq(searchTool))
 * agent.run("What is Scala programming language?", tools)
 * }}}
 */
object DuckDuckGoSearchTool {

  private val DuckDuckGoApiUrl = "https://api.duckduckgo.com/"

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
   */
  def create(
    config: DuckDuckGoSearchConfig = DuckDuckGoSearchConfig()
  ): ToolFunction[Map[String, Any], DuckDuckGoSearchResult] =
    ToolBuilder[Map[String, Any], DuckDuckGoSearchResult](
      name = "duckduckgo_search",
      description = "Search the web for definitions, facts, and quick answers using DuckDuckGo. " +
        "Best for factual queries and definitions. Does not provide full web search results.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        searchQuery <- extractor.getString("search_query")
        _           <- if (searchQuery.trim.isEmpty) Left("search_query cannot be empty") else Right(())
        result      <- search(searchQuery, config)
      } yield result
    }.build()

  /**
   * Default DuckDuckGo search tool with standard configuration.
   */
  val tool: ToolFunction[Map[String, Any], DuckDuckGoSearchResult] = create()

  private val SAFE_SEARCH   = "1"
  private val UNSAFE_SEARCH = "-1"
  private def search(
    query: String,
    config: DuckDuckGoSearchConfig
  ): Either[String, DuckDuckGoSearchResult] = {

    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val safeSearch   = if (config.safeSearch) SAFE_SEARCH else UNSAFE_SEARCH
    val url =
      s"$DuckDuckGoApiUrl?q=$encodedQuery&format=json&no_html=1&skip_disambig=0&t=llm4s&safesearch=$safeSearch"

    val responseEither: Either[String, Response] =
      Try {
        requests.get(
          url = url,
          headers = Map(
            "User-Agent" -> "llm4s-duckduckgo-search/1.0"
          ),
          readTimeout = config.timeoutMs
        )
      }.toEither.left.map(e => s"DuckDuckGo search request failed: ${e.getMessage}")

    responseEither.flatMap(response =>
      if (response.statusCode == 200) {
        Try {
          val json = ujson.read(response.text())
          parseResults(query, json, config)

        }.toEither.left.map(e => s"DuckDuckGo search JSON parsing failed: ${e.getMessage}")
      } else {
        Left(s"DuckDuckGo search returned status ${response.statusCode}: ${response.text()}")
      }
    )
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
  private def parseResults(query: String, json: ujson.Value, config: DuckDuckGoSearchConfig): DuckDuckGoSearchResult = {
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
