package org.llm4s.config

import org.llm4s.error.ValidationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.http.HttpResponse.*
import org.llm4s.config.DefaultConfig
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderKind }
import org.llm4s.llmconnect.config.MistralConfig

import scala.util.Try

final case class DiscoveredModel(
  name: ModelName,
  provider: ProviderKind,
  metadata: Map[String, String] = Map.empty
)

private[llm4s] trait ProviderModelLister:
  def listModels(
    config: NamedProviderConfig,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]]

private[llm4s] object ProviderModelListers:

  object OpenAI extends ProviderModelLister:
    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      listOpenAICompatibleModels(
        config = config,
        expected = ProviderKind.OpenAI,
        provider = ProviderKind.OpenAI,
        defaultBaseUrl = DefaultConfig.DEFAULT_OPENAI_BASE_URL,
        httpClient = httpClient
      )

  object OpenRouter extends ProviderModelLister:
    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      listOpenAICompatibleModels(
        config = config,
        expected = ProviderKind.OpenRouter,
        provider = ProviderKind.OpenRouter,
        defaultBaseUrl = DefaultConfig.DEFAULT_OPENROUTER_BASE_URL,
        httpClient = httpClient
      )

  object Anthropic extends ProviderModelLister:
    private val AnthropicVersion = "2023-06-01"
    private val DefaultLimit     = "100"

    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      for
        anthropic <- config.requireProvider(ProviderKind.Anthropic)
        apiKey    <- anthropic.requireApiKey
        baseUrl = anthropic.baseUrlOrDefault(DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)
        models <- listAnthropicModels(baseUrl, apiKey, httpClient)
      yield models

    private def listAnthropicModels(
      baseUrl: BaseUrl,
      apiKey: org.llm4s.types.ProviderModelTypes.ApiKey,
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

  object Gemini extends ProviderModelLister:
    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      for
        gemini <- config.requireProvider(ProviderKind.Gemini)
        apiKey <- gemini.requireApiKey
        baseUrl = gemini.baseUrlOrDefault(DefaultConfig.DEFAULT_GEMINI_BASE_URL)
        models <- listGeminiModels(baseUrl, apiKey, httpClient)
      yield models

    private def listGeminiModels(
      baseUrl: BaseUrl,
      apiKey: org.llm4s.types.ProviderModelTypes.ApiKey,
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

  object DeepSeek extends ProviderModelLister:
    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      listOpenAICompatibleModels(
        config = config,
        expected = ProviderKind.DeepSeek,
        provider = ProviderKind.DeepSeek,
        defaultBaseUrl = DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL,
        httpClient = httpClient
      )

  object Mistral extends ProviderModelLister:
    def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
      listOpenAICompatibleModels(
        config = config,
        expected = ProviderKind.Mistral,
        provider = ProviderKind.Mistral,
        defaultBaseUrl = MistralConfig.DEFAULT_BASE_URL,
        modelsPath = "/v1/models",
        httpClient = httpClient
      )

  object Ollama extends ProviderModelLister:
    def listModels(
      config: NamedProviderConfig,
      httpClient: Llm4sHttpClient
    ): Result[List[DiscoveredModel]] =
      for
        ollama  <- config.requireProvider(ProviderKind.Ollama)
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

  private def listOpenAICompatibleModels(
    config: NamedProviderConfig,
    expected: ProviderKind,
    provider: ProviderKind,
    defaultBaseUrl: String,
    modelsPath: String = "/models",
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]] =
    for
      normalized <- config.requireProvider(expected)
      apiKey     <- normalized.requireApiKey
      baseUrl = normalized.baseUrlOrDefault(defaultBaseUrl)
      headers = authHeaders(normalized, apiKey)
      response <- httpClient
        .getResult(s"${baseUrl.asUrl}$modelsPath", headers = headers, timeout = 10000)
        .mapServiceError(provider.toString.toLowerCase, "Failed to discover models")
      okResponse   <- response.ensureSuccess(provider.toString.toLowerCase)
      jsonResponse <- okResponse.toJson("responseBody")
      models       <- parseOpenAICompatibleModels(jsonResponse.body, provider)
    yield models

  private def authHeaders(
    config: NamedProviderConfig,
    apiKey: org.llm4s.types.ProviderModelTypes.ApiKey
  ): Map[String, String] =
    val base =
      Map("Authorization" -> s"Bearer ${apiKey.asKey}") ++
        config.organization.map(org => "OpenAI-Organization" -> org)

    if config.provider == ProviderKind.OpenRouter then
      base ++ Map(
        "HTTP-Referer" -> "https://github.com/llm4s/llm4s",
        "X-Title"      -> "LLM4S"
      )
    else base

  private def parseOpenAICompatibleModels(
    json: ujson.Value,
    provider: ProviderKind
  ): Result[List[DiscoveredModel]] =
    val dataResult =
      Try(json("data").arr.toList).toResult.left
        .map(err => ValidationError("data", s"Missing or invalid models payload: ${err.message}"))

    dataResult.flatMap: data =>
      data.foldLeft[Result[List[DiscoveredModel]]](Right(Nil)):
        case (accResult, modelJson) =>
          for
            acc    <- accResult
            parsed <- parseOpenAICompatibleModel(modelJson, provider)
          yield parsed match
            case Some(model) => acc :+ model
            case None        => acc

  private def parseOpenAICompatibleModel(
    json: ujson.Value,
    provider: ProviderKind
  ): Result[Option[DiscoveredModel]] =
    val obj = json.obj
    obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(id) =>
        val metadata =
          List(
            obj.get("created").flatMap(_.numOpt).map(n => "created" -> n.toLong.toString),
            obj.get("owned_by").flatMap(_.strOpt).map("ownedBy" -> _),
            obj.get("name").flatMap(_.strOpt).map("displayName" -> _),
            obj.get("description").flatMap(_.strOpt).map("description" -> _),
          ).flatten.toMap

        Right(Some(DiscoveredModel(ModelName(id), provider, metadata)))

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
        Right(Some(DiscoveredModel(ModelName(id), ProviderKind.Anthropic, metadata)))

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
    for
      models        <- parseGeminiModels(json)
      nextPageToken <- parseOptionalString(json, "nextPageToken")
    yield GeminiPage(models, nextPageToken)

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
        Right(Some(DiscoveredModel(ModelName(modelId), ProviderKind.Gemini, metadata)))

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
              provider = ProviderKind.Ollama,
              metadata = metadata
            )
          )

  private def parseOptionalString(json: ujson.Value, field: String): Result[Option[String]] =
    Right(json.obj.get(field).flatMap(_.strOpt).filter(_.nonEmpty))

  private def parseOptionalBoolean(json: ujson.Value, field: String): Result[Option[Boolean]] =
    json.obj.get(field) match
      case None                                  => Right(None)
      case Some(value) if value.boolOpt.nonEmpty => Right(value.boolOpt)
      case Some(_)                               => Left(ValidationError(field, s"Invalid boolean value for `$field`"))
