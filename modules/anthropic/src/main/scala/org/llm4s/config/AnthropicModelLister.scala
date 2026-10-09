package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, ModelName }
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.concurrent.duration.*

/**
 * Model lister for the Anthropic provider, using the paginated `/v1/models` endpoint.
 *
 * Anthropic does not serve the OpenAI-compatible `/models` shape, so it cannot use
 * `ProviderModelListers.openAICompatible` (in `llm4s-openai-compatible`). This was
 * `ProviderModelListers.Anthropic` until the provider moved to `llm4s-anthropic`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object AnthropicModelLister extends ProviderModelLister:
  private val AnthropicVersion = "2023-06-01"
  private val DefaultLimit     = "100"

  private type JsonObject = upickle.core.LinkedHashMap[String, ujson.Value]

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      anthropic <- config.requireProvider(ProviderId("anthropic"))
      apiKey    <- anthropic.requireApiKey
      baseUrl = anthropic.baseUrlOrDefault(AnthropicConfig.DEFAULT_BASE_URL)
      models <- listAnthropicModels(baseUrl, apiKey, httpClient)
    yield models

  private def listAnthropicModels(
    baseUrl: BaseUrl,
    apiKey: ApiKey,
    httpClient: Llm4sHttpClient,
    afterId: Option[String] = None,
    acc: List[DiscoveredModel] = Nil
  ): Result[List[DiscoveredModel]] =
    for
      response <- httpClient
        .get(
          s"${baseUrl.asUrl}/v1/models",
          headers = Map(
            "x-api-key"         -> apiKey.asKey,
            "anthropic-version" -> AnthropicVersion
          ),
          params = Map("limit" -> DefaultLimit) ++ afterId.map("after_id" -> _),
          timeout = 10.seconds
        )
        .mapServiceError("anthropic", "Failed to discover models")
      okResponse <- response.ensureSuccess("anthropic")
      json       <- readBody(okResponse.body)
      page       <- parseAnthropicPage(json)
      all = acc ++ page.models
      models <-
        if page.hasMore then
          page.lastId match
            case Some(lastId) => listAnthropicModels(baseUrl, apiKey, httpClient, Some(lastId), all)
            case None =>
              Left(ValidationError("last_id", "Anthropic models response set has_more=true without last_id"))
        else Right(all)
    yield models

  /**
   * The listing's body, read with `BoundedJson` so that one nested too deeply is refused before it is
   * parsed ([[https://github.com/llm4s/llm4s/issues/1660 #1660]]).
   */
  private def readBody(body: String): Result[ujson.Value] =
    BoundedJson
      .read(body)
      .left
      .map(err => ValidationError("responseBody", s"Failed to parse JSON response: ${err.message}"))

  /**
   * The `data` array's models. Every step uses an accessor that returns an `Option`: `json("data")`
   * or `.obj` on an unexpected shape throws `ujson.Value.InvalidData`, whose message renders the
   * whole value recursively (#1660). An entry that is not an object is skipped, as one without an
   * `id` always was.
   */
  private def parseAnthropicModels(json: JsonObject): Result[List[DiscoveredModel]] =
    json
      .get("data")
      .flatMap(_.arrOpt)
      .toRight(ValidationError("data", "Missing or invalid models payload: expected an object with a `data` array"))
      .map(_.toList.flatMap(parseAnthropicModel))

  final private case class AnthropicPage(
    models: List[DiscoveredModel],
    hasMore: Boolean,
    lastId: Option[String]
  )

  private def parseAnthropicPage(body: ujson.Value): Result[AnthropicPage] =
    for
      json <- body.objOpt.toRight(
        ValidationError("data", "Missing or invalid models payload: expected an object with a `data` array")
      )
      models  <- parseAnthropicModels(json)
      hasMore <- parseOptionalBoolean(json, "has_more").map(_.getOrElse(false))
      lastId  <- parseOptionalString(json, "last_id")
    yield AnthropicPage(models, hasMore, lastId)

  private def parseAnthropicModel(json: ujson.Value): Option[DiscoveredModel] =
    json.objOpt.flatMap: obj =>
      obj
        .get("id")
        .flatMap(_.strOpt)
        .filter(_.nonEmpty)
        .map: id =>
          val metadata =
            List(
              obj.get("display_name").flatMap(_.strOpt).map("displayName" -> _),
              obj.get("created_at").flatMap(_.strOpt).map("createdAt" -> _),
              obj.get("type").flatMap(_.strOpt).map("type" -> _),
            ).flatten.toMap
          DiscoveredModel(ModelName(id), ProviderId("anthropic"), metadata)

  private def parseOptionalString(
    json: JsonObject,
    field: String
  ): Result[Option[String]] =
    Right(json.get(field).flatMap(_.strOpt).filter(_.nonEmpty))

  private def parseOptionalBoolean(
    json: JsonObject,
    field: String
  ): Result[Option[Boolean]] =
    json.get(field) match
      case None                                  => Right(None)
      case Some(value) if value.boolOpt.nonEmpty => Right(value.boolOpt)
      case Some(_)                               => Left(ValidationError(field, s"Invalid boolean value for `$field`"))
