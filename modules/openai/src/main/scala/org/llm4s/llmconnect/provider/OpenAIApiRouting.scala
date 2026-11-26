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
 *  1. a client provider other than `openai` (Requesty), or a config whose own provider id is not `openai` (a
 *     hand-built config pointed at OpenRouter): Chat Completions;
 *  1. a model the registry marks `mode: responses`: Responses, wherever the client points;
 *  1. a base URL that is not OpenAI's own ([[isOpenAI]]): Chat Completions;
 *  1. a model the registry knows whose entry does not list `/v1/responses`, whether it lists other endpoints or
 *     none: Chat Completions;
 *  1. otherwise (the entry lists `/v1/responses`, or the registry does not know the model): Responses.
 *
 * The registry knows a model by its id as given (ignoring case and a router prefix); a fine-tune
 * (`ft:gpt-4o-mini-2024-07-18:acme::x`) by the registry's `ft:<base>` entry, else by its base model's own entry
 * ([[OpenAIReasoning.describingEntry]]), so a fine-tune is routed as its base family is. The bundled `ft:` entries
 * list no endpoints, so a fine-tune they cover stays on Chat Completions; a fine-tune none of these resolve is one
 * the registry does not know.
 *
 * Rule 6 is data-positive because a model the registry knows but does not list may be served on Chat Completions
 * only, as the search-preview models are (verified against OpenAI on 2026-11-26); a registry override that adds
 * `/v1/responses` to a model's endpoints opts it in. OpenAI's regional hosts (`us.`, `eu.`, `ae.api.openai.com`)
 * are not treated as the default base URL, so they stay on Chat Completions unless `api` is set.
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
    lazy val described: Option[(String, ModelMetadata)] =
      Try(OpenAIReasoning.describingEntry(model, registry)).toOption.flatten
    lazy val entry: Option[ModelMetadata] = described.map(_._2)
    // the model as the reason names it: a fine-tune also names the entry it was routed by
    lazy val named: String = described match
      case Some((id, _)) if id != model => s"$model (by the registry's $id)"
      case _                            => model
    if provider != OpenAIProvider then
      Choice(OpenAIApi.ChatCompletions, s"the provider is ${provider.asString}, which is served on Chat Completions")
    else if config.providerId != OpenAIProvider then
      Choice(
        OpenAIApi.ChatCompletions,
        s"the config's provider is ${config.providerId.asString}, which is served on Chat Completions"
      )
    else if entry.exists(_.mode == ModelMode.Responses) then
      Choice(OpenAIApi.Responses, s"$named is served only on the Responses API")
    else if !isOpenAI(config.baseUrl) then
      Choice(OpenAIApi.ChatCompletions, s"the base URL is not OpenAI's (${loggable(config.baseUrl)})")
    else
      entry match
        case Some(meta) if !meta.supportedEndpoints.contains(ResponsesEndpoint) =>
          Choice(
            OpenAIApi.ChatCompletions,
            s"the model registry does not list $named for $ResponsesEndpoint; set api = responses to use it there"
          )
        case Some(_) => Choice(OpenAIApi.Responses, s"the model registry lists $named for $ResponsesEndpoint")
        case None =>
          Choice(OpenAIApi.Responses, s"the model registry does not know $model, and this is OpenAI's own API")

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
