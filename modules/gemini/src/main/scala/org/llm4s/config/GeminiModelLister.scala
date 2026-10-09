package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.types.ProviderModelTypes.{ ApiKey, ModelName }
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.concurrent.duration.*

/**
 * Model lister for the Gemini provider, using the paginated `models` endpoint.
 *
 * Gemini does not serve the OpenAI-compatible `/models` shape, so it cannot use
 * `ProviderModelListers.openAICompatible` (in `llm4s-openai-compatible`). This was
 * `ProviderModelListers.Gemini` until the provider moved to `llm4s-gemini`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object GeminiModelLister extends ProviderModelLister:
  private type JsonObject = upickle.core.LinkedHashMap[String, ujson.Value]

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
        .get(
          s"${baseUrl.asUrl}/models",
          headers = Map("x-goog-api-key" -> apiKey.asKey),
          params = Map("pageSize" -> "1000") ++ pageToken.map("pageToken" -> _),
          timeout = 10.seconds
        )
        .mapServiceError("gemini", "Failed to discover models")
      okResponse <- response.ensureSuccess("gemini")
      json       <- readBody(okResponse.body)
      page       <- parseGeminiPage(json)
      all = acc ++ page.models
      models <- page.nextPageToken match
        case Some(token) if token.nonEmpty => listGeminiModels(baseUrl, apiKey, httpClient, Some(token), all)
        case _                             => Right(all)
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
   * The `models` array's entries. Every step uses an accessor that returns an `Option`: `json("models")`
   * or `.obj` on an unexpected shape throws `ujson.Value.InvalidData`, whose message renders the
   * whole value recursively (#1660). An entry that is not an object is skipped, as one without a
   * `name` always was.
   */
  private def parseGeminiModels(json: JsonObject): Result[List[DiscoveredModel]] =
    json
      .get("models")
      .flatMap(_.arrOpt)
      .toRight(
        ValidationError("models", "Missing or invalid Gemini models payload: expected an object with a `models` array")
      )
      .map(_.toList.flatMap(parseGeminiModel))

  final private case class GeminiPage(
    models: List[DiscoveredModel],
    nextPageToken: Option[String]
  )

  private def parseGeminiPage(body: ujson.Value): Result[GeminiPage] =
    for
      json <- body.objOpt.toRight(
        ValidationError("models", "Missing or invalid Gemini models payload: expected an object with a `models` array")
      )
      models <- parseGeminiModels(json)
    yield GeminiPage(models, json.get("nextPageToken").flatMap(_.strOpt).filter(_.nonEmpty))

  private def parseGeminiModel(json: ujson.Value): Option[DiscoveredModel] =
    json.objOpt.flatMap: obj =>
      obj
        .get("name")
        .flatMap(_.strOpt)
        .filter(_.nonEmpty)
        .map: name =>
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
          DiscoveredModel(ModelName(modelId), ProviderId("gemini"), metadata)
