package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import upickle.default._
import org.llm4s.config.ExaSearchToolConfig
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.util.Redaction
import scala.util.control.NonFatal

sealed trait SearchType {
  def value: String
}

object SearchType {
  case object Auto   extends SearchType { val value = "auto"   }
  case object Neural extends SearchType { val value = "neural" }
  case object Fast   extends SearchType { val value = "fast"   }
  case object Deep   extends SearchType { val value = "deep"   }

  def fromString(value: String): Option[SearchType] = value.trim.toLowerCase match {
    case "auto"   => Some(Auto)
    case "neural" => Some(Neural)
    case "fast"   => Some(Fast)
    case "deep"   => Some(Deep)
    case _        => None
  }
}

sealed trait Category {
  def value: String
}

object Category {
  case object Company         extends Category { val value = "company"          }
  case object ResearchPaper   extends Category { val value = "research paper"   }
  case object News            extends Category { val value = "news"             }
  case object Pdf             extends Category { val value = "pdf"              }
  case object Github          extends Category { val value = "github"           }
  case object Tweet           extends Category { val value = "tweet"            }
  case object PersonalSite    extends Category { val value = "personal site"    }
  case object LinkedinProfile extends Category { val value = "linkedin profile" }
  case object FinancialReport extends Category { val value = "financial report" }

  def fromString(value: String): Option[Category] = value.trim.toLowerCase match {
    case "company"          => Some(Company)
    case "research paper"   => Some(ResearchPaper)
    case "news"             => Some(News)
    case "pdf"              => Some(Pdf)
    case "github"           => Some(Github)
    case "tweet"            => Some(Tweet)
    case "personal site"    => Some(PersonalSite)
    case "linkedin profile" => Some(LinkedinProfile)
    case "financial report" => Some(FinancialReport)
    case _                  => None
  }
}

/**
 * Runtime configuration for Exa Search requests.
 * Allows overriding defaults and providing advanced parameters via extraParams.
 *
 * @param timeoutMs Request timeout in milliseconds
 * @param numResults Number of results (mandatory, default 10)
 * @param searchType Search type (mandatory, default Auto)
 * @param maxCharacters Max text characters (mandatory, default 500)
 * @param maxAgeHours Max content age in hours (default 1)
 * @param category Data category (optional, default None)
 * @param additionalQueries Additional queries for deep search (optional, default None)
 * @param userLocation User location for local search (optional, default None)
 * @param livecrawlTimeout Timeout for livecrawl (optional, default None)
 * @param extraParams Advanced parameters merged into the request body
 */
case class ExaSearchConfig(
  timeoutMs: Int = 10000,
  numResults: Int = 10,
  searchType: SearchType = SearchType.Auto,
  maxCharacters: Int = 500,
  maxAgeHours: Int = 1,
  category: Option[Category] = None,
  additionalQueries: Option[List[String]] = None,
  userLocation: Option[String] = None,
  livecrawlTimeout: Option[Int] = None,
  extraParams: Map[String, ujson.Value] = Map.empty
)

case class ExaResult(
  title: String,
  url: String,
  id: Option[String] = None,
  publishedDate: Option[String] = None,
  author: Option[String] = None,
  text: Option[String] = None,
  highlights: Option[List[String]] = None,
  highlightScores: Option[List[Double]] = None,
  summary: Option[String] = None,
  favicon: Option[String] = None,
  image: Option[String] = None,
  subPages: Option[List[ExaResult]] = None
)

object ExaResult {
  implicit val exaResultRW: ReadWriter[ExaResult] = macroRW[ExaResult]
}

case class ExaSearchResult(
  query: String,
  results: List[ExaResult],
  requestId: Option[String] = None,
  searchType: Option[String] = None
)

object ExaSearchResult {
  implicit val exaSearchResultRW: ReadWriter[ExaSearchResult] = macroRW[ExaSearchResult]
}

object ExaSearchTool {

  // ===== Validation Helpers =====
  // These validators delegate to ExaSearchToolConfig from the config layer.
  // This maintains consistency between config loading and tool creation validation,
  // while respecting proper layering (tool layer can depend on config layer).
  //
  // ConfigurationError is converted to ValidationError for consistency with tool error types.

  private[llm4s] def validateApiKey(key: String): Result[String] =
    ExaSearchToolConfig
      .validateApiKey(key)
      .left
      .map {
        case ConfigurationError(msg, keys) =>
          ValidationError(keys.headOption.getOrElse("apiKey"), msg)
        case other => ValidationError("apiKey", other.message)
      }

  private[llm4s] def validateHttps(url: String): Result[String] =
    ExaSearchToolConfig
      .validateHttps(url)
      .left
      .map {
        case ConfigurationError(msg, keys) =>
          ValidationError(keys.headOption.getOrElse("apiUrl"), msg)
        case other => ValidationError("apiUrl", other.message)
      }

  private[llm4s] def validateNumResults(n: Int): Result[Int] =
    ExaSearchToolConfig
      .validateNumResults(n)
      .left
      .map {
        case ConfigurationError(msg, keys) =>
          ValidationError(keys.headOption.getOrElse("numResults"), msg)
        case other => ValidationError("numResults", other.message)
      }

  private[llm4s] def validateMaxCharacters(n: Int): Result[Int] =
    ExaSearchToolConfig
      .validateMaxCharacters(n)
      .left
      .map {
        case ConfigurationError(msg, keys) =>
          ValidationError(keys.headOption.getOrElse("maxCharacters"), msg)
        case other => ValidationError("maxCharacters", other.message)
      }

  private[llm4s] def validateSearchType(s: String): Result[String] =
    ExaSearchToolConfig
      .validateSearchType(s)
      .left
      .map {
        case ConfigurationError(msg, keys) =>
          ValidationError(keys.headOption.getOrElse("searchType"), msg)
        case other => ValidationError("searchType", other.message)
      }

  private[llm4s] def validateQuery(q: String): Result[String] = {
    val trimmed = q.trim
    if (trimmed.nonEmpty) Right(trimmed)
    else Left(ValidationError.required("query"))
  }

  private[llm4s] def validateTimeoutMs(timeout: Int): Result[Int] =
    if (timeout >= 1000 && timeout <= 300000) Right(timeout)
    else Left(ValidationError.invalid("timeoutMs", s"must be between 1000 and 300000 (1s to 5min), got $timeout"))
  private[llm4s] def validateUserLocation(location: Option[String]): Result[Option[String]] =
    location match {
      case Some(loc) =>
        val trimmed = loc.trim
        if (trimmed.nonEmpty) Right(Some(trimmed))
        else Left(ValidationError.invalid("userLocation", "must not be empty"))
      case None => Right(None)
    }

  private[llm4s] def validateAdditionalQueries(queries: Option[List[String]]): Result[Option[List[String]]] =
    queries match {
      case Some(qs) =>
        val trimmed = qs.map(_.trim).filter(_.nonEmpty)
        if (trimmed.isEmpty) {
          Left(ValidationError.invalid("additionalQueries", "must contain at least one non-empty query"))
        } else if (trimmed.size != qs.size) {
          Left(ValidationError.invalid("additionalQueries", "contains empty queries"))
        } else {
          Right(Some(trimmed))
        }
      case None => Right(None)
    }

  private[llm4s] def validateSearchTypeValue(searchType: SearchType): Result[SearchType] =
    Right(searchType) // Already type-safe via sealed trait

  /**
   * Validate entire ExaSearchToolConfig.
   * Used by create() to ensure all fields are valid.
   */
  private def validateToolConfig(config: ExaSearchToolConfig): Result[ExaSearchToolConfig] =
    for {
      validatedApiKey        <- validateApiKey(config.apiKey)
      validatedApiUrl        <- validateHttps(config.apiUrl)
      validatedNumResults    <- validateNumResults(config.numResults)
      validatedSearchType    <- validateSearchType(config.searchType)
      validatedMaxCharacters <- validateMaxCharacters(config.maxCharacters)
    } yield ExaSearchToolConfig(
      apiKey = validatedApiKey,
      apiUrl = validatedApiUrl,
      numResults = validatedNumResults,
      searchType = validatedSearchType,
      maxCharacters = validatedMaxCharacters
    )

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Exa Search parameters")
    .withProperty(
      Schema.property(
        "query",
        Schema.string("The search query - be specific and clear about what you're looking for")
      )
    )

  /**
   * Validate runtime ExaSearchConfig values.
   * Validates all externally configurable inputs at the boundary
   */
  private def validateSearchConfig(config: ExaSearchConfig): Result[ExaSearchConfig] =
    for {
      validatedTimeoutMs         <- validateTimeoutMs(config.timeoutMs)
      validatedNumResults        <- validateNumResults(config.numResults)
      validatedMaxCharacters     <- validateMaxCharacters(config.maxCharacters)
      validatedUserLocation      <- validateUserLocation(config.userLocation)
      validatedAdditionalQueries <- validateAdditionalQueries(config.additionalQueries)
    } yield config.copy(
      timeoutMs = validatedTimeoutMs,
      numResults = validatedNumResults,
      maxCharacters = validatedMaxCharacters,
      userLocation = validatedUserLocation,
      additionalQueries = validatedAdditionalQueries
    )

  /**
   * Create an Exa search tool with explicit configuration.
   *
   * Security: This tool makes external HTTPS calls to the Exa API.
   * Ensure proper API key management and network access controls.
   *
   * @param toolConfig The Exa API configuration (must use HTTPS)
   * @param config Optional configuration overrides (will be validated)
   * @param httpClient HTTP client for making requests (injectable for testing)
   * @param restoreInterrupt Function to restore interrupt flag (injectable for testing)
   * @return Right(ToolFunction) if valid, Left(ValidationError) otherwise
   */
  def create(
    toolConfig: ExaSearchToolConfig,
    config: Option[ExaSearchConfig] = None,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
    restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
  ): Result[ToolFunction[Map[String, Any], ExaSearchResult]] =
    // Validate entire config using shared validators
    for {
      validatedConfig <- validateToolConfig(toolConfig)

      searchType <- SearchType
        .fromString(validatedConfig.searchType)
        .toRight(
          ValidationError.invalid("searchType", s"'${validatedConfig.searchType}' is not a valid search type")
        )

      // Build default config from validated toolConfig
      defaultConfig = ExaSearchConfig(
        numResults = validatedConfig.numResults,
        searchType = searchType,
        maxCharacters = validatedConfig.maxCharacters
      )

      // If override config provided, validate it; otherwise use default
      finalConfig <- config match {
        case Some(overrideConfig) => validateSearchConfig(overrideConfig)
        case None                 => Right(defaultConfig)
      }

      tool = ToolBuilder[Map[String, Any], ExaSearchResult](
        name = "exa_search",
        description =
          "Search the web using Exa's AI-powered search engine. Use this for semantic and intent-based searches that understand natural language queries (e.g., 'companies working on AI safety', 'recent papers about transformers'). Returns high-quality structured results with titles, URLs, text snippets, authors, and publication dates. Best for research, technical documentation, finding specific companies or people, and discovering recent content.",
        schema = createSchema
      ).withHandler { extractor =>
        for {
          rawQuery <- extractor.getString("query")
          query    <- validateQuery(rawQuery).left.map(_.message) // Convert Result to Either[String, String]
          result   <- search(query, finalConfig, validatedConfig, httpClient, restoreInterrupt)
        } yield result
      }.build()
    } yield tool

  /**
   * Create an Exa search tool with explicit API key and defaults.
   *
   * Security: This tool makes external HTTPS calls to the Exa API.
   * The provided API URL must use HTTPS protocol.
   *
   * @param apiKey The Exa API key (must not be empty)
   * @param apiUrl The Exa API URL (must use HTTPS, default: https://api.exa.ai)
   * @param config Optional request configuration
   * @param httpClient HTTP client for making requests (injectable for testing)
   * @param restoreInterrupt Function to restore interrupt flag (injectable for testing)
   * @return Right(ToolFunction) if valid, Left(ValidationError) otherwise
   */
  def withApiKey(
    apiKey: String,
    apiUrl: String = "https://api.exa.ai",
    config: Option[ExaSearchConfig] = None,
    httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
    restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
  ): Result[ToolFunction[Map[String, Any], ExaSearchResult]] =
    // Validate only the parameters this function receives
    for {
      validatedApiKey <- validateApiKey(apiKey)
      validatedApiUrl <- validateHttps(apiUrl)

      toolConfig = ExaSearchToolConfig(
        apiKey = validatedApiKey,
        apiUrl = validatedApiUrl,
        numResults = 10,
        searchType = "auto",
        maxCharacters = 500
      )

      tool <- create(toolConfig, config, httpClient, restoreInterrupt)
    } yield tool

  private[search] def search(
    query: String,
    config: ExaSearchConfig,
    toolConfig: ExaSearchToolConfig,
    httpClient: Llm4sHttpClient,
    restoreInterrupt: () => Unit
  ): Either[String, ExaSearchResult] = {

    val url  = s"${toolConfig.apiUrl}/search"
    val body = buildRequestBody(query, config)

    // Catch only non-fatal exceptions. Fatal errors (OOM, StackOverflow, etc.) will crash fast.
    // InterruptedException is handled explicitly to restore the interrupt flag.
    val responseEither: Either[String, HttpResponse] =
      try
        Right(
          httpClient.post(
            url = url,
            headers = Map(
              "Content-Type" -> "application/json",
              "x-api-key"    -> toolConfig.apiKey,
              "User-Agent"   -> "llm4s-exa-search/1.0"
            ),
            body = ujson.write(body),
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
          Right(parseResponse(json, query))
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
        // Redact sensitive information and truncate for logging
        val body = Redaction.redactForLogging(response.body)
        Left(s"Exa search returned status ${response.statusCode}: $body")
      }
    }
  }

  private[search] def buildRequestBody(
    query: String,
    config: ExaSearchConfig
  ): ujson.Obj = {
    val contents = ujson.Obj(
      "text" -> ujson.Obj("maxCharacters" -> ujson.Num(config.maxCharacters))
    )
    contents("maxAgeHours") = ujson.Num(config.maxAgeHours)
    config.livecrawlTimeout.foreach(timeout => contents("livecrawlTimeout") = ujson.Num(timeout))

    val body = ujson.Obj(
      "query"      -> ujson.Str(query),
      "type"       -> ujson.Str(config.searchType.value),
      "numResults" -> ujson.Num(config.numResults),
      "contents"   -> contents
    )

    config.category.foreach(c => body("category") = ujson.Str(c.value))
    config.userLocation.foreach(loc => body("userLocation") = ujson.Str(loc))
    config.additionalQueries.foreach(queries => body("additionalQueries") = ujson.Arr(queries.map(ujson.Str(_)): _*))

    // Merge extraParams
    config.extraParams.foreach { case (key, value) =>
      if (key == "contents") {
        value match {
          case obj: ujson.Obj =>
            obj.value.foreach { case (k, v) => contents(k) = v }
          case _ =>
            body(key) = value
        }
      } else {
        body(key) = value
      }
    }

    body
  }

  private[search] def parseResponse(json: ujson.Value, query: String): ExaSearchResult = {
    val resultsArr = json.obj.get("results").flatMap(_.arrOpt).getOrElse(Nil)
    val requestId  = json.obj.get("requestId").flatMap(_.strOpt)
    val searchType = json.obj.get("searchType").flatMap(_.strOpt)

    def parseExaResult(r: ujson.Value): ExaResult = {
      val obj = r.obj
      ExaResult(
        title = obj.get("title").flatMap(_.strOpt).getOrElse(""),
        url = obj.get("url").flatMap(_.strOpt).getOrElse(""),
        id = obj.get("id").flatMap(_.strOpt),
        publishedDate = obj.get("publishedDate").flatMap(_.strOpt),
        author = obj.get("author").flatMap(_.strOpt),
        text = obj.get("text").flatMap(_.strOpt),
        highlights = obj.get("highlights").flatMap(_.arrOpt).map(_.flatMap(_.strOpt).toList),
        highlightScores = obj.get("highlightScores").flatMap(_.arrOpt).map(_.flatMap(_.numOpt).toList),
        summary = obj.get("summary").flatMap(_.strOpt),
        favicon = obj.get("favicon").flatMap(_.strOpt),
        image = obj.get("image").flatMap(_.strOpt),
        subPages = obj.get("subPages").flatMap(_.arrOpt).map(_.map(parseExaResult).toList)
      )
    }

    val results = resultsArr.toList.map(parseExaResult)

    ExaSearchResult(query, results, requestId, searchType)
  }
}
