package org.llm4s.config

import org.llm4s.error.ValidationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.http.HttpResponse.*
import org.llm4s.config.DefaultConfig
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderId }
import org.llm4s.llmconnect.config.MistralConfig

import scala.util.Try

/**
 * A model discovered from a provider's live model-listing endpoint.
 *
 *  @param name     the model identifier as reported by the provider
 *  @param provider the `ProviderId` that owns this model
 *  @param metadata optional key/value pairs of additional model metadata (e.g. display name, token limits)
 */
final case class DiscoveredModel(
  name: ModelName,
  provider: ProviderId,
  metadata: Map[String, String] = Map.empty
)

/** Discovers available models from a provider's live API endpoint. */
trait ProviderModelLister:
  /**
   * Fetches the list of available models for the given provider configuration.
   *
   *  @param config     the validated named provider configuration containing credentials and base URL
   *  @param httpClient the HTTP client to use for API requests
   *  @return `Right` with the list of discovered models, or `Left` with an error
   */
  def listModels(
    config: NamedProviderConfig,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]]

/**
 * Model listers for the providers built into `llm4s-core`, and the factory
 * behind most of them.
 *
 * A provider module outside core supplies its own lister the same way: call
 * [[openAICompatible]] if the provider serves the OpenAI `/models` shape, or
 * implement [[ProviderModelLister]] if it does not.
 */
object ProviderModelListers:

  /**
   * A lister for any provider serving the OpenAI-compatible `/models` endpoint.
   *
   * This is the shape most providers have, so a provider module supplying its
   * own descriptor can usually call this rather than implement
   * [[ProviderModelLister]] from scratch.
   *
   *  @param provider       the `ProviderId` that owns the returned models; the config section must name it
   *  @param defaultBaseUrl base URL used when the section does not override it
   *  @param modelsPath     path of the listing endpoint, relative to the base URL
   */
  def openAICompatible(
    provider: ProviderId,
    defaultBaseUrl: String,
    modelsPath: String = "/models"
  ): ProviderModelLister =
    new ProviderModelLister:
      def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
        listOpenAICompatibleModels(
          config = config,
          provider = provider,
          defaultBaseUrl = defaultBaseUrl,
          modelsPath = modelsPath,
          httpClient = httpClient
        )

  /** Model lister for the OpenAI provider. */
  val OpenAI: ProviderModelLister =
    openAICompatible(ProviderId("openai"), DefaultConfig.DEFAULT_OPENAI_BASE_URL)

  /** Model lister for the OpenRouter provider. */
  val OpenRouter: ProviderModelLister =
    openAICompatible(ProviderId("openrouter"), DefaultConfig.DEFAULT_OPENROUTER_BASE_URL)

  /** Model lister for the Requesty provider. */
  val Requesty: ProviderModelLister =
    openAICompatible(ProviderId("requesty"), DefaultConfig.DEFAULT_REQUESTY_BASE_URL)

  /** Model lister for the DeepSeek provider using the OpenAI-compatible models endpoint. */
  val DeepSeek: ProviderModelLister =
    openAICompatible(ProviderId("deepseek"), DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)

  /** Model lister for the Mistral provider using the OpenAI-compatible models endpoint. */
  val Mistral: ProviderModelLister =
    openAICompatible(ProviderId("mistral"), MistralConfig.DEFAULT_BASE_URL, modelsPath = "/v1/models")

  private def listOpenAICompatibleModels(
    config: NamedProviderConfig,
    provider: ProviderId,
    defaultBaseUrl: String,
    modelsPath: String,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]] =
    for
      normalized <- config.requireProvider(provider)
      apiKey     <- normalized.requireApiKey
      baseUrl = normalized.baseUrlOrDefault(defaultBaseUrl)
      headers = authHeaders(normalized, apiKey)
      response <- httpClient
        .getResult(s"${baseUrl.asUrl}$modelsPath", headers = headers, timeout = 10000)
        .mapServiceError(provider.asString, "Failed to discover models")
      okResponse   <- response.ensureSuccess(provider.asString)
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

    if config.provider == ProviderId("openrouter") then
      base ++ Map(
        "HTTP-Referer" -> "https://github.com/llm4s/llm4s",
        "X-Title"      -> "LLM4S"
      )
    else base

  private def parseOpenAICompatibleModels(
    json: ujson.Value,
    provider: ProviderId
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
    provider: ProviderId
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
