package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import upickle.default._
import org.llm4s.config.ExaSearchToolConfig
import org.llm4s.error.ValidationError
import scala.util.Try
import java.net.http.{ HttpClient => JHttpClient, HttpRequest, HttpResponse => JHttpResponse }
import java.net.URI
import java.time.Duration

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
 * @param maxAgeHours Max content age in hours (optional, default None)
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

/**
 * Simple HTTP response wrapper.
 */
private[search] case class HttpResponse(
  statusCode: Int,
  body: String
)

/**
 * Abstraction for HTTP client to enable dependency injection and testing.
 */
private[search] trait BaseHttpClient {
  def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse
}

/**
 * Java HttpClient implementation using JDK 11+ built-in HTTP client.
 */
private[search] class JavaHttpClient extends BaseHttpClient {
  private val client = JHttpClient.newHttpClient()

  override def post(
    url: String,
    headers: Map[String, String],
    body: String,
    timeout: Int
  ): HttpResponse = {
    val requestBuilder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeout.toLong))
      .POST(HttpRequest.BodyPublishers.ofString(body))

    headers.foreach { case (key, value) =>
      requestBuilder.header(key, value)
    }

    val response = client.send(
      requestBuilder.build(),
      JHttpResponse.BodyHandlers.ofString()
    )

    HttpResponse(response.statusCode(), response.body())
  }
}

object ExaSearchTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Exa Search parameters")
    .withProperty(
      Schema.property(
        "query",
        Schema.string("The search query - be specific and clear about what you're looking for")
      )
    )

  /**
   * Sanitize error messages to prevent information leakage.
   * Removes sensitive details while keeping useful debugging info.
   */
  private def sanitizeErrorMessage(statusCode: Int, responseBody: String): String =
    statusCode match {
      case 401 => "Authentication failed. Please verify your API key is valid."
      case 403 => "Access forbidden. Your API key may not have permission for this operation."
      case 429 => "Rate limit exceeded. Please reduce request frequency and try again later."
      case code if code >= 500 && code < 600 =>
        "External search service is temporarily unavailable. Please try again later."
      case 400 =>
        // For 400 errors, include a sanitized version of the error if it's safe
        if (responseBody.length < 200 && !responseBody.contains("key") && !responseBody.contains("token")) {
          s"Invalid request: ${responseBody.take(150)}"
        } else {
          "Invalid request. Please check your query parameters."
        }
      case _ =>
        s"Search request failed with status $statusCode. Please try again or contact support."
    }

  /**
   * Create an Exa search tool with explicit configuration.
   *
   * Security: This tool makes external HTTPS calls to the Exa API.
   * Ensure proper API key management and network access controls.
   *
   * @param toolConfig The Exa API configuration (must use HTTPS)
   * @param config Optional configuration overrides
   * @param httpClient HTTP client for making requests (injectable for testing)
   * @return A configured ToolFunction
   */
  def create(
    toolConfig: ExaSearchToolConfig,
    config: Option[ExaSearchConfig] = None,
    httpClient: BaseHttpClient = new JavaHttpClient()
  ): ToolFunction[Map[String, Any], ExaSearchResult] = {
    // Validate security boundaries at tool creation time
    val apiUrl = toolConfig.apiUrl.toLowerCase.trim
    if (!apiUrl.startsWith("https://")) {
      throw new IllegalArgumentException(
        ValidationError
          .invalid("apiUrl", "must use HTTPS protocol for secure communication")
          .message
      )
    }
    if (toolConfig.apiKey.trim.isEmpty) {
      throw new IllegalArgumentException(
        ValidationError.required("apiKey").message
      )
    }

    ToolBuilder[Map[String, Any], ExaSearchResult](
      name = "exa_search",
      description =
        "Search the web using Exa's AI-powered search engine. Use this for semantic and intent-based searches that understand natural language queries (e.g., 'companies working on AI safety', 'recent papers about transformers'). Returns high-quality structured results with titles, URLs, text snippets, authors, and publication dates. Best for research, technical documentation, finding specific companies or people, and discovering recent content.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        rawQuery <- extractor.getString("query")
        query = rawQuery.trim
        _ <- if (query.nonEmpty) Right(()) else Left(ValidationError.required("query").message)

        searchType <- SearchType
          .fromString(toolConfig.searchType)
          .toRight(
            ValidationError
              .invalid("searchType", s"'${toolConfig.searchType}' is not a valid search type")
              .message
          )

        finalConfig = config.getOrElse(
          ExaSearchConfig(
            numResults = toolConfig.numResults,
            searchType = searchType,
            maxCharacters = toolConfig.maxCharacters
          )
        )

        result <- search(query, finalConfig, toolConfig, httpClient)
      } yield result
    }.build()
  }

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
   * @return A configured ToolFunction
   * @throws IllegalArgumentException if security boundaries are violated
   */
  def withApiKey(
    apiKey: String,
    apiUrl: String = "https://api.exa.ai",
    config: Option[ExaSearchConfig] = None,
    httpClient: BaseHttpClient = new JavaHttpClient()
  ): ToolFunction[Map[String, Any], ExaSearchResult] = {
    val toolConfig = ExaSearchToolConfig(
      apiKey = apiKey,
      apiUrl = apiUrl,
      numResults = 10,
      searchType = "auto",
      maxCharacters = 500
    )
    create(toolConfig, config, httpClient)
  }

  private[search] def search(
    query: String,
    config: ExaSearchConfig,
    toolConfig: ExaSearchToolConfig,
    httpClient: BaseHttpClient
  ): Either[String, ExaSearchResult] = {

    val url  = s"${toolConfig.apiUrl}/search"
    val body = buildRequestBody(query, config)

    val responseEither: Either[String, HttpResponse] =
      Try {
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
      }.toEither.left.map { e =>
        // Sanitize exception messages to avoid leaking internal details
        e match {
          case _: java.net.http.HttpTimeoutException =>
            s"Search request timed out after ${config.timeoutMs}ms. Please try again with a simpler query."
          case _: java.net.UnknownHostException =>
            "Unable to reach search service. Please check network connectivity."
          case _: java.net.ConnectException =>
            "Failed to connect to search service. The service may be temporarily unavailable."
          case _ =>
            // Generic error without exposing stack traces or internal details
            "Search request failed due to a network error. Please try again."
        }
      }

    responseEither.flatMap { response =>
      if (response.statusCode == 200) {
        // Parse successful response
        Try {
          val json = ujson.read(response.body)
          parseResponse(json, query)
        }.toEither.left.map { e =>
          // Sanitize parsing errors
          e match {
            case _: ujson.ParseException =>
              "Failed to parse search results. The response format may be invalid."
            case _ =>
              "Failed to process search results. Please try again."
          }
        }
      } else {
        // Use sanitized error messages for non-200 responses
        Left(sanitizeErrorMessage(response.statusCode, response.body))
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
