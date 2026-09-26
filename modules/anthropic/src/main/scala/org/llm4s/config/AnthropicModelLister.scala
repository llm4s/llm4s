package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, ModelName }
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Model lister for the Anthropic provider, using the paginated `/v1/models` endpoint.
 *
 * Anthropic does not serve the OpenAI-compatible `/models` shape, so it cannot use
 * [[ProviderModelListers.openAICompatible]]. This was
 * `ProviderModelListers.Anthropic` until the provider moved to `llm4s-anthropic`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
object AnthropicModelLister extends ProviderModelLister:
  private val AnthropicVersion = "2023-06-01"
  private val DefaultLimit     = "100"

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
        .getResult(
          s"${baseUrl.asUrl}/v1/models",
          headers = Map(
            "x-api-key"         -> apiKey.asKey,
            "anthropic-version" -> AnthropicVersion
          ),
          params = Map("limit" -> DefaultLimit) ++ afterId.map("after_id" -> _),
          timeout = 10000
        )
        .mapServiceError("anthropic", "Failed to discover models")
      okResponse   <- response.ensureSuccess("anthropic")
      jsonResponse <- okResponse.toJson("responseBody")
      page         <- parseAnthropicPage(jsonResponse.body)
      all = acc ++ page.models
      models <-
        if page.hasMore then
          page.lastId match
            case Some(lastId) => listAnthropicModels(baseUrl, apiKey, httpClient, Some(lastId), all)
            case None =>
              Left(ValidationError("last_id", "Anthropic models response set has_more=true without last_id"))
        else Right(all)
    yield models

  private def parseAnthropicModels(json: ujson.Value): Result[List[DiscoveredModel]] =
    val dataResult =
      Try(json("data").arr.toList).toResult.left
        .map(err => ValidationError("data", s"Missing or invalid models payload: ${err.message}"))

    dataResult.flatMap: data =>
      data.foldLeft[Result[List[DiscoveredModel]]](Right(Nil)):
        case (accResult, modelJson) =>
          for
            acc    <- accResult
            parsed <- parseAnthropicModel(modelJson)
          yield parsed match
            case Some(model) => acc :+ model
            case None        => acc

  final private case class AnthropicPage(
    models: List[DiscoveredModel],
    hasMore: Boolean,
    lastId: Option[String]
  )

  private def parseAnthropicPage(json: ujson.Value): Result[AnthropicPage] =
    for
      models  <- parseAnthropicModels(json)
      hasMore <- parseOptionalBoolean(json, "has_more").map(_.getOrElse(false))
      lastId  <- parseOptionalString(json, "last_id")
    yield AnthropicPage(models, hasMore, lastId)

  private def parseAnthropicModel(json: ujson.Value): Result[Option[DiscoveredModel]] =
    val obj = json.obj
    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(id) =>
        val metadata =
          List(
            obj.get("display_name").flatMap(_.strOpt).map("displayName" -> _),
            obj.get("created_at").flatMap(_.strOpt).map("createdAt" -> _),
            obj.get("type").flatMap(_.strOpt).map("type" -> _),
          ).flatten.toMap
        Right(Some(DiscoveredModel(ModelName(id), ProviderId("anthropic"), metadata)))

  private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] =
    Right(json.obj.get(field).flatMap(_.strOpt).filter(_.nonEmpty))

  private def parseOptionalBoolean(json: ujson.Value, field: String): Result[Option[Boolean]] =
    json.obj.get(field) match
      case None                                  => Right(None)
      case Some(value) if value.boolOpt.nonEmpty => Right(value.boolOpt)
      case Some(_)                               => Left(ValidationError(field, s"Invalid boolean value for `$field`"))
