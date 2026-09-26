package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, ModelName }
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Model lister for the Gemini provider, using the paginated `models` endpoint.
 *
 * Gemini does not serve the OpenAI-compatible `/models` shape, so it cannot use
 * [[ProviderModelListers.openAICompatible]]. This was
 * `ProviderModelListers.Gemini` until the provider moved to `llm4s-gemini`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
object GeminiModelLister extends ProviderModelLister:
  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      gemini <- config.requireProvider(ProviderId("gemini"))
      apiKey <- gemini.requireApiKey
      baseUrl = gemini.baseUrlOrDefault(GeminiConfig.DEFAULT_BASE_URL)
      models <- listGeminiModels(baseUrl, apiKey, httpClient)
    yield models

  private def listGeminiModels(
    baseUrl: BaseUrl,
    apiKey: ApiKey,
    httpClient: Llm4sHttpClient,
    pageToken: Option[String] = None,
    acc: List[DiscoveredModel] = Nil
  ): Result[List[DiscoveredModel]] =
    for
      response <- httpClient
        .getResult(
          s"${baseUrl.asUrl}/models",
          headers = Map("x-goog-api-key" -> apiKey.asKey),
          params = Map("pageSize" -> "1000") ++ pageToken.map("pageToken" -> _),
          timeout = 10000
        )
        .mapServiceError("gemini", "Failed to discover models")
      okResponse   <- response.ensureSuccess("gemini")
      jsonResponse <- okResponse.toJson("responseBody")
      page         <- parseGeminiPage(jsonResponse.body)
      all = acc ++ page.models
      models <- page.nextPageToken match
        case Some(token) if token.nonEmpty => listGeminiModels(baseUrl, apiKey, httpClient, Some(token), all)
        case _                             => Right(all)
    yield models

  private def parseGeminiModels(json: ujson.Value): Result[List[DiscoveredModel]] =
    val modelsResult =
      Try(json("models").arr.toList).toResult.left
        .map(err => ValidationError("models", s"Missing or invalid Gemini models payload: ${err.message}"))

    modelsResult.flatMap: models =>
      models.foldLeft[Result[List[DiscoveredModel]]](Right(Nil)):
        case (accResult, modelJson) =>
          for
            acc    <- accResult
            parsed <- parseGeminiModel(modelJson)
          yield parsed match
            case Some(model) => acc :+ model
            case None        => acc

  final private case class GeminiPage(
    models: List[DiscoveredModel],
    nextPageToken: Option[String]
  )

  private def parseGeminiPage(json: ujson.Value): Result[GeminiPage] =
    parseGeminiModels(json).map: models =>
      val nextPageToken = json.obj.get("nextPageToken").flatMap(_.strOpt).filter(_.nonEmpty)
      GeminiPage(models, nextPageToken)

  private def parseGeminiModel(json: ujson.Value): Result[Option[DiscoveredModel]] =
    val obj = json.obj
    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(name) =>
        val modelId = name.stripPrefix("models/")
        val metadata =
          List(
            obj.get("displayName").flatMap(_.strOpt).map("displayName" -> _),
            obj.get("description").flatMap(_.strOpt).map("description" -> _),
            obj.get("inputTokenLimit").flatMap(_.numOpt).map(n => "inputTokenLimit" -> n.toLong.toString),
            obj.get("outputTokenLimit").flatMap(_.numOpt).map(n => "outputTokenLimit" -> n.toLong.toString),
            obj
              .get("supportedGenerationMethods")
              .flatMap(_.arrOpt)
              .map(methods => "supportedGenerationMethods" -> methods.flatMap(_.strOpt).mkString(",")),
          ).flatten.toMap
        Right(Some(DiscoveredModel(ModelName(modelId), ProviderId("gemini"), metadata)))
