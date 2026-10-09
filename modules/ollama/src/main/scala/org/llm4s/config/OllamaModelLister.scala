package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.concurrent.duration.*

/**
 * Model lister for the Ollama provider, using the local `/api/tags` endpoint.
 *
 * Ollama does not serve the OpenAI-compatible `/models` shape, so unlike most
 * providers it cannot use `ProviderModelListers.openAICompatible` (in `llm4s-openai-compatible`). This was
 * `ProviderModelListers.Ollama` until the provider moved to `llm4s-ollama`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object OllamaModelLister extends ProviderModelLister:
  def listModels(
    config: NamedProviderConfig,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]] =
    for
      ollama  <- config.requireProvider(ProviderId("ollama"))
      baseUrl <- ollama.requireBaseUrl
      models  <- listOllamaModels(baseUrl, httpClient)
    yield models

  private def listOllamaModels(baseUrl: BaseUrl, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      response <- httpClient
        .get(s"${baseUrl.asUrl}/api/tags", timeout = 10.seconds)
        .mapServiceError("ollama", "Failed to discover models")
      okResponse <- response.ensureSuccess("ollama")
      json       <- readBody(okResponse.body)
      models     <- parseOllamaModels(json)
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
  private def parseOllamaModels(json: ujson.Value): Result[List[DiscoveredModel]] =
    json.objOpt
      .flatMap(_.get("models"))
      .flatMap(_.arrOpt)
      .toRight(
        ValidationError("models", "Missing or invalid Ollama models payload: expected an object with a `models` array")
      )
      .map(_.toList.flatMap(parseOllamaModel))

  private def parseOllamaModel(json: ujson.Value): Option[DiscoveredModel] =
    json.objOpt.flatMap: obj =>
      obj
        .get("name")
        .flatMap(_.strOpt)
        .filter(_.nonEmpty)
        .map: name =>
          val details = obj.get("details").flatMap(_.objOpt).map(_.toMap).getOrElse(Map.empty)

          val metadata =
            List(
              obj.get("modified_at").flatMap(_.strOpt).map("modifiedAt" -> _),
              obj.get("size").flatMap(_.numOpt).map(n => "size" -> n.toLong.toString),
              obj.get("digest").flatMap(_.strOpt).map("digest" -> _),
              details.get("format").flatMap(_.strOpt).map("format" -> _),
              details.get("family").flatMap(_.strOpt).map("family" -> _),
              details.get("parameter_size").flatMap(_.strOpt).map("parameterSize" -> _),
              details.get("quantization_level").flatMap(_.strOpt).map("quantizationLevel" -> _),
            ).flatten.toMap

          DiscoveredModel(
            name = ModelName(name),
            provider = ProviderId("ollama"),
            metadata = metadata
          )
