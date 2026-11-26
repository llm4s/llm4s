package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ OpenAIApi, OpenAIConfig, ProviderConfig }
import org.llm4s.model.{ ModelMetadata, ModelMode, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ProviderId }

import java.net.URI
import java.util.Locale
import scala.util.Try

/**
 * Which API an [[OpenAIClient]] speaks, decided once when the client is built. First match wins:
 *
 *  1. a config that is not an [[OpenAIConfig]] (Azure): Chat Completions;
 *  1. `api` set on the config: as set;
 *  1. a provider other than `openai` (Requesty): Chat Completions;
 *  1. a model the registry marks `mode: responses`: Responses, wherever the client points;
 *  1. a base URL that is not OpenAI's own ([[isOpenAI]]): Chat Completions;
 *  1. a model whose registry entry lists endpoints without `/v1/responses`: Chat Completions;
 *  1. otherwise: Responses.
 *
 * The per-model facts come from the model registry, never from a list here. `resolve` never throws: a registry
 * lookup that fails counts as a model the registry does not know.
 */
private[provider] object OpenAIApiRouting:

  /** The API chosen, and why, in words for a user reading the log. */
  final case class Choice(api: OpenAIApi, reason: String)

  private val OpenAIProvider    = ProviderId("openai")
  private val ResponsesEndpoint = "/v1/responses"
  private val OpenAIHost        = "api.openai.com"
  private val DefaultHttpsPort  = 443
  private val NoPort            = -1

  def resolve(config: ProviderConfig, provider: ProviderId, model: String, registry: ModelRegistryService): Choice =
    config match
      case openAI: OpenAIConfig =>
        openAI.api match
          case Some(api) => Choice(api, s"the config sets api = ${OpenAIApi.configValue(api)}")
          case None      => byDefault(openAI, provider, model, registry)
      case other =>
        Choice(OpenAIApi.ChatCompletions, s"${other.getClass.getSimpleName} is served on Chat Completions only")

  private def byDefault(config: OpenAIConfig, provider: ProviderId, model: String, registry: ModelRegistryService) =
    lazy val entry: Option[ModelMetadata] = Try(OpenAIReasoning.openAIEntry(model, registry)).toOption.flatten
    if provider != OpenAIProvider then
      Choice(OpenAIApi.ChatCompletions, s"the provider is ${provider.asString}, which is served on Chat Completions")
    else if entry.exists(_.mode == ModelMode.Responses) then
      Choice(OpenAIApi.Responses, s"$model is served only on the Responses API")
    else if !isOpenAI(config.baseUrl) then
      Choice(OpenAIApi.ChatCompletions, s"the base URL is not OpenAI's (${loggable(config.baseUrl)})")
    else if entry.exists(notOnResponses) then
      Choice(OpenAIApi.ChatCompletions, s"$model is not served on $ResponsesEndpoint")
    else Choice(OpenAIApi.Responses, "the base URL is OpenAI's own API and no rule says otherwise")

  /** An entry that lists its endpoints, without `/v1/responses`; one that lists none says nothing. */
  private def notOnResponses(meta: ModelMetadata): Boolean =
    meta.supportedEndpoints.nonEmpty && !meta.supportedEndpoints.contains(ResponsesEndpoint)

  /** Whether `baseUrl` is OpenAI's own API: https, host api.openai.com (any case), port absent or 443. */
  def isOpenAI(baseUrl: BaseUrl): Boolean =
    Try(new URI(baseUrl.asUrl.trim)).toOption.exists { uri =>
      uri.getScheme == "https" &&
      Option(uri.getHost).exists(_.toLowerCase(Locale.ROOT) == OpenAIHost) &&
      (uri.getPort == NoPort || uri.getPort == DefaultHttpsPort) &&
      uri.getRawUserInfo == null
    }

  /** The URL's scheme, host, port and path only: never its user info, query or fragment. */
  private def loggable(baseUrl: BaseUrl): String =
    Try(new URI(baseUrl.asUrl.trim)).toOption
      .filter(uri => uri.getScheme != null && uri.getHost != null)
      .map { uri =>
        val port = if uri.getPort == NoPort then "" else s":${uri.getPort}"
        s"${uri.getScheme}://${uri.getHost}$port${Option(uri.getRawPath).getOrElse("")}"
      }
      .getOrElse("a URL without a parseable host")
