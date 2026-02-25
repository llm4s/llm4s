package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._
import org.llm4s.config.BraveSearchToolConfig

import scala.util.control.NonFatal
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.util.Redaction

/**
 * Type-class describing a Brave Search category (Web, Image, Video, News).
 *
 * Each case object encodes the API endpoint, the tool name exposed to the LLM,
 * a human-readable description, result parsing logic, and how to map a
 * [[SafeSearch]] level to the string the Brave API expects.
 *
 * @tparam R The Scala type that represents a search result for this category
 */
sealed trait BraveSearchCategory[R] {

  /** API path segment appended to the Brave Search base URL. */
  def endpoint: String

  /** Name used to register this tool with a [[org.llm4s.toolapi.ToolRegistry]]. */
  def toolName: String

  /** Natural-language description shown to the LLM. */
  def description: String

  /**
   * Parse the raw Brave API response JSON into a typed result.
   *
   * @param json  The parsed API response body
   * @param query The original search query (included in the result for traceability)
   */
  def parseResults(json: ujson.Value, query: String): R

  /**
   * Map a [[SafeSearch]] level to the string expected by this category's API endpoint.
   *
   * Some categories (e.g. Image) do not support all levels and remap them.
   *
   * @param safeSearch The requested safe-search level
   */
  def mapSafeSearch(safeSearch: SafeSearch): String
}
object BraveSearchCategory {
  case object Web extends BraveSearchCategory[BraveWebSearchResult] {
    val endpoint    = "web/search"
    val toolName    = "brave_web_search"
    val description = "Searches the web using Brave Search"

    def mapSafeSearch(safeSearch: SafeSearch): String = safeSearch.value

    def parseResults(json: ujson.Value, query: String): BraveWebSearchResult = {
      val webResultsArr = for {
        web     <- json.obj.get("web")
        results <- web.obj.get("results")
        arr     <- results.arrOpt
      } yield arr.toList

      val finalResults = webResultsArr.getOrElse(Nil).map { r =>
        BraveWebResult(
          title = r.obj.get("title").flatMap(_.strOpt).getOrElse(""),
          url = r.obj.get("url").flatMap(_.strOpt).getOrElse(""),
          description = r.obj.get("description").flatMap(_.strOpt).getOrElse("")
        )
      }
      BraveWebSearchResult(query, finalResults)
    }
  }
  case object Image extends BraveSearchCategory[BraveImageSearchResult] {
    val endpoint    = "images/search"
    val toolName    = "brave_image_search"
    val description = "Searches for images using Brave Search"

    def mapSafeSearch(safeSearch: SafeSearch): String = safeSearch match {
      case SafeSearch.Moderate => "strict" // Images don't support moderate, map to strict
      case other               => other.value
    }

    def parseResults(json: ujson.Value, query: String): BraveImageSearchResult = {
      val imageResultsArr = for {
        results <- json.obj.get("results")
        arr     <- results.arrOpt
      } yield arr.toList

      val finalResults = imageResultsArr.getOrElse(Nil).map { r =>
        BraveImageResult(
          title = r.obj.get("title").flatMap(_.strOpt).getOrElse(""),
          url = r.obj.get("url").flatMap(_.strOpt).getOrElse(""),
          thumbnail = r.obj.get("thumbnail").flatMap(_.obj.get("src")).flatMap(_.strOpt).getOrElse("")
        )
      }
      BraveImageSearchResult(query, finalResults)
    }
  }
  case object Video extends BraveSearchCategory[BraveVideoSearchResult] {
    val endpoint    = "videos/search"
    val toolName    = "brave_video_search"
    val description = "Searches for videos using Brave Search"

    def mapSafeSearch(safeSearch: SafeSearch): String = safeSearch.value

    def parseResults(json: ujson.Value, query: String): BraveVideoSearchResult = {
      val videoResultsArr = for {
        results <- json.obj.get("results")
        arr     <- results.arrOpt
      } yield arr.toList

      val finalResults = videoResultsArr.getOrElse(Nil).map { r =>
        BraveVideoResult(
          title = r.obj.get("title").flatMap(_.strOpt).getOrElse(""),
          url = r.obj.get("url").flatMap(_.strOpt).getOrElse(""),
          description = r.obj.get("description").flatMap(_.strOpt).getOrElse("")
        )
      }
      BraveVideoSearchResult(query, finalResults)
    }
  }
  case object News extends BraveSearchCategory[BraveNewsSearchResult] {
    val endpoint    = "news/search"
    val toolName    = "brave_news_search"
    val description = "Searches for news using Brave Search"

    def mapSafeSearch(safeSearch: SafeSearch): String = safeSearch.value

    def parseResults(json: ujson.Value, query: String): BraveNewsSearchResult = {
      val newsResultsArr = for {
        results <- json.obj.get("results")
        arr     <- results.arrOpt
      } yield arr.toList

      val finalResults = newsResultsArr.getOrElse(Nil).map { r =>
        BraveNewsResult(
          title = r.obj.get("title").flatMap(_.strOpt).getOrElse(""),
          url = r.obj.get("url").flatMap(_.strOpt).getOrElse(""),
          description = r.obj.get("description").flatMap(_.strOpt).getOrElse("")
        )
      }
      BraveNewsSearchResult(query, finalResults)
    }
  }
}

/**
 * Safe-search filtering level for Brave Search API requests.
 *
 * Controls whether adult or otherwise sensitive content is filtered from results.
 */
sealed trait SafeSearch {

  /** The raw string value sent to the Brave Search API. */
  def value: String
}

object SafeSearch {

  /** No content filtering — all results are returned. */
  case object Off extends SafeSearch { val value = "off" }

  /** Moderate filtering — explicit imagery is suppressed but text results are not filtered. */
  case object Moderate extends SafeSearch { val value = "moderate" }

  /** Strict filtering — explicit content is fully suppressed. */
  case object Strict extends SafeSearch { val value = "strict" }

  /**
   * Parse a string value from configuration into a SafeSearch enum.
   * @param value The string value (e.g., "off", "moderate", "strict")
   * @return The corresponding SafeSearch enum, defaulting to Moderate for invalid values
   */
  def fromString(value: String): SafeSearch = value.toLowerCase match {
    case "off"      => Off
    case "moderate" => Moderate
    case "strict"   => Strict
    case _          => Moderate // Safe default
  }
}

/**
 * Runtime configuration for Brave Search API requests.
 *
 * @param timeoutMs   HTTP request timeout in milliseconds
 * @param count       Number of results to return per request
 * @param safeSearch  Safe-search filtering level (default: [[SafeSearch.Strict]])
 * @param extraParams Additional key-value parameters merged into every request
 */
case class BraveSearchConfig(
  timeoutMs: Int = 10000,
  count: Int = 5,
  safeSearch: SafeSearch = SafeSearch.Strict,
  extraParams: Map[String, ujson.Value] = Map.empty
)

/** News search result container returned by [[BraveSearchCategory.News]]. */
case class BraveNewsSearchResult(
  query: String,
  results: List[BraveNewsResult]
)

/** A single news article result from the Brave News Search API. */
case class BraveNewsResult(
  title: String,
  url: String,
  description: String
)

/** Video search result container returned by [[BraveSearchCategory.Video]]. */
case class BraveVideoSearchResult(
  query: String,
  results: List[BraveVideoResult]
)

/** A single video result from the Brave Video Search API. */
case class BraveVideoResult(
  title: String,
  url: String,
  description: String
)

/** Image search result container returned by [[BraveSearchCategory.Image]]. */
case class BraveImageSearchResult(
  query: String,
  results: List[BraveImageResult]
)

/** A single image result from the Brave Image Search API. */
case class BraveImageResult(
  title: String,
  url: String,
  thumbnail: String
)

/** Web search result container returned by [[BraveSearchCategory.Web]]. */
case class BraveWebSearchResult(
  query: String,
  results: List[BraveWebResult]
)

/** A single organic web result from the Brave Web Search API. */
case class BraveWebResult(
  title: String,
  url: String,
  description: String
)

object BraveWebResult {
  implicit val braveWebResultRW: ReadWriter[BraveWebResult] = macroRW[BraveWebResult]
}

object BraveWebSearchResult {
  implicit val braveWebSearchResultRW: ReadWriter[BraveWebSearchResult] = macroRW[BraveWebSearchResult]
}

object BraveImageSearchResult {
  implicit val braveImageSearchResultRW: ReadWriter[BraveImageSearchResult] = macroRW[BraveImageSearchResult]
}

object BraveImageResult {
  implicit val braveImageResultRW: ReadWriter[BraveImageResult] = macroRW[BraveImageResult]
}

object BraveVideoResult {
  implicit val braveVideoResultRW: ReadWriter[BraveVideoResult] = macroRW[BraveVideoResult]
}

object BraveVideoSearchResult {
  implicit val braveVideoSearchResultRW: ReadWriter[BraveVideoSearchResult] = macroRW[BraveVideoSearchResult]
}

object BraveNewsResult {
  implicit val braveNewsResultRW: ReadWriter[BraveNewsResult] = macroRW[BraveNewsResult]
}

object BraveNewsSearchResult {
  implicit val braveNewsSearchResultRW: ReadWriter[BraveNewsSearchResult] = macroRW[BraveNewsSearchResult]
}

object BraveSearchTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Brave Search parameters")
    .withProperty(
      Schema.property(
        "search_query",
        Schema.string("The search query")
      )
    )

  /**
   * Create a Brave search tool with explicit configuration.
   *
   * @param toolConfig The Brave API configuration (apiKey, apiUrl, count, safeSearch)
   * @param category The search category (Web, Image, Video, or News)
   * @param config Optional configuration overrides (defaults use values from toolConfig)
   * @return A configured ToolFunction
   */
  def create[R: ReadWriter](
    toolConfig: BraveSearchToolConfig,
    category: BraveSearchCategory[R] = BraveSearchCategory.Web,
    config: Option[BraveSearchConfig] = None,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
    restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
  ): Result[ToolFunction[Map[String, Any], R]] =
    ToolBuilder[Map[String, Any], R](
      name = category.toolName,
      description = category.description,
      schema = createSchema
    ).withHandler { extractor =>
      for {
        query <- extractor.getString("search_query")
        // Use provided config if set, otherwise parse from loaded config
        finalConfig = config.getOrElse(
          BraveSearchConfig(
            count = toolConfig.count,
            safeSearch = SafeSearch.fromString(toolConfig.safeSearch)
          )
        )
        result <- search(query, finalConfig, toolConfig, category, httpClient, restoreInterrupt)
      } yield result
    }.buildSafe()

  /**
   * Create a Brave search tool with explicit API key and optional overrides.
   *
   * Uses hardcoded defaults for apiUrl, count, and safeSearch.
   * For full control, use create() with a BraveSearchToolConfig instead.
   *
   * @param apiKey The Brave Search API key to use
   * @param apiUrl The Brave API URL (defaults to production endpoint)
   * @param category The search category (Web, Image, Video, or News)
   * @param config Optional search config overrides (count, safeSearch)
   * @return A configured ToolFunction ready to use
   */
  def withApiKey[R: ReadWriter](
    apiKey: String,
    apiUrl: String = "https://api.search.brave.com/res/v1",
    category: BraveSearchCategory[R] = BraveSearchCategory.Web,
    config: Option[BraveSearchConfig] = None,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
    restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
  ): Result[ToolFunction[Map[String, Any], R]] = {
    // Hardcoded defaults when using withApiKey
    val braveTool = BraveSearchToolConfig(
      apiKey = apiKey,
      apiUrl = apiUrl,
      count = 5,
      safeSearch = "moderate"
    )
    create(braveTool, category, config, httpClient, restoreInterrupt)
  }

  private[search] def search[R](
    query: String,
    config: BraveSearchConfig,
    braveTool: BraveSearchToolConfig,
    category: BraveSearchCategory[R],
    httpClient: Llm4sHttpClient,
    restoreInterrupt: () => Unit
  ): Either[String, R] = {

    // Build query parameters from config
    val params = Map(
      "q"          -> query,
      "count"      -> config.count.toString,
      "safesearch" -> category.mapSafeSearch(config.safeSearch)
    ) ++ config.extraParams.map { case (k, v) => k -> v.toString }

    val url = s"${braveTool.apiUrl}/${category.endpoint}"

    // Catch only non-fatal exceptions. Fatal errors (OOM, StackOverflow, etc.) will crash fast.
    // InterruptedException is handled explicitly to restore the interrupt flag.
    val responseEither: Either[String, HttpResponse] =
      try
        Right(
          httpClient.get(
            url = url,
            params = params,
            headers = Map(
              "Accept"               -> "application/json",
              "Accept-Encoding"      -> "gzip",
              "X-Subscription-Token" -> braveTool.apiKey,
              "User-Agent"           -> "llm4s-brave-search/1.0"
            ),
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
          Right(category.parseResults(json, query))
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
        Left(s"Brave ${category.toolName} returned status ${response.statusCode}: $body")
      }
    }
  }
}
