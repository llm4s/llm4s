package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.HttpResponse.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Model lister for the Ollama provider, using the local `/api/tags` endpoint.
 *
 * Ollama does not serve the OpenAI-compatible `/models` shape, so unlike most
 * providers it cannot use [[ProviderModelListers.openAICompatible]]. This was
 * `ProviderModelListers.Ollama` until the provider moved to `llm4s-ollama`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
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
        .getResult(s"${baseUrl.asUrl}/api/tags", timeout = 10000)
        .mapServiceError("ollama", "Failed to discover models")
      okResponse   <- response.ensureSuccess("ollama")
      jsonResponse <- okResponse.toJson("responseBody")
      models       <- parseOllamaModels(jsonResponse.body)
    yield models

  private def parseOllamaModels(json: ujson.Value): Result[List[DiscoveredModel]] =
    val modelsResult =
      Try(json("models").arr.toList).toResult.left
        .map(err => ValidationError("models", s"Missing or invalid Ollama models payload: ${err.message}"))

    modelsResult.flatMap: models =>
      models.foldLeft[Result[List[DiscoveredModel]]](Right(Nil)):
        case (accResult, modelJson) =>
          for
            acc    <- accResult
            parsed <- parseOllamaModel(modelJson)
          yield parsed match
            case Some(model) => acc :+ model
            case None        => acc

  private def parseOllamaModel(json: ujson.Value): Result[Option[DiscoveredModel]] =
    val obj = json.obj
    obj.get("name").flatMap(_.strOpt).filter(_.nonEmpty) match
      case None =>
        Right(None)
      case Some(name) =>
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

        Right(ModelName(name)).map: modelName =>
          Some(
            DiscoveredModel(
              name = modelName,
              provider = ProviderId("ollama"),
              metadata = metadata
            )
          )
