package org.llm4s.toolapi.builtin.search

import org.llm4s.toolapi._
import upickle.default._
import org.llm4s.config.ExaSearchToolConfig
import scala.util.Try
import requests.Response

sealed trait SearchType {
  def value: String
}

object SearchType {
  case object Auto   extends SearchType { val value = "auto"   }
  case object Neural extends SearchType { val value = "neural" }
  case object Fast   extends SearchType { val value = "fast"   }
  case object Deep   extends SearchType { val value = "deep"   }

  def fromString(value: String): SearchType = value.trim.toLowerCase match {
    case "auto"   => Auto
    case "neural" => Neural
    case "fast"   => Fast
    case "deep"   => Deep
    case _        => Auto
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
 * @param maxCharacters Max text characters (mandatory, default 3000)
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
   * Create an Exa search tool with explicit configuration.
   *
   * @param toolConfig The Exa API configuration
   * @param config Optional configuration overrides
   * @return A configured ToolFunction
   */
  def create(
    toolConfig: ExaSearchToolConfig,
    config: Option[ExaSearchConfig] = None
  ): ToolFunction[Map[String, Any], ExaSearchResult] =
    ToolBuilder[Map[String, Any], ExaSearchResult](
      name = "exa_search",
      description = "Search the web using Exa. Supports auto, neural, and keyword search with rich content extraction.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        query <- extractor.getString("query")

        finalConfig = config.getOrElse(
          ExaSearchConfig(
            numResults = toolConfig.numResults,
            searchType = SearchType.fromString(toolConfig.searchType),
            maxCharacters = toolConfig.maxCharacters,
          )
        )

        result <- search(query, finalConfig, toolConfig)
      } yield result
    }.build()

  /**
   * Create an Exa search tool with explicit API key and defaults.
   *
   * @param apiKey The Exa API key
   * @param apiUrl The Exa API URL
   * @param config Optional request configuration
   * @return A configured ToolFunction
   */
  def withApiKey(
    apiKey: String,
    apiUrl: String = "https://api.exa.ai",
    config: Option[ExaSearchConfig] = None
  ): ToolFunction[Map[String, Any], ExaSearchResult] = {
    val toolConfig = ExaSearchToolConfig(
      apiKey = apiKey,
      apiUrl = apiUrl,
      numResults = 10,
      searchType = "auto",
      maxCharacters = 500,
    )
    create(toolConfig, config)
  }

  private def search(
    query: String,
    config: ExaSearchConfig,
    toolConfig: ExaSearchToolConfig
  ): Either[String, ExaSearchResult] = {

    val url  = s"${toolConfig.apiUrl}/search"
    val body = buildRequestBody(query, config)

    val responseEither: Either[String, Response] =
      Try {
        requests.post(
          url = url,
          headers = Map(
            "Content-Type" -> "application/json",
            "x-api-key"    -> toolConfig.apiKey,
            "User-Agent"   -> "llm4s-exa-search/1.0"
          ),
          data = ujson.write(body),
          readTimeout = config.timeoutMs
        )
      }.toEither.left.map(e => s"Exa search request failed: ${e.getMessage}")

    responseEither.flatMap { response =>
      if (response.statusCode == 200) {
        Try {
          val json = ujson.read(response.text())
          parseResponse(json, query)
        }.toEither.left.map(e => s"Exa JSON parsing failed: ${e.getMessage}")
      } else {
        Left(s"Exa returned status ${response.statusCode}: ${response.text()}")
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
