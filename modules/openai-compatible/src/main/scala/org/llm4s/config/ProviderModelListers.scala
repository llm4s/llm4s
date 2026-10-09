package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ ApiKey, NamedProviderConfig, ProviderId }
import org.llm4s.error.ValidationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.http.HttpResponse.*
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

import scala.concurrent.duration.*

/**
 * The factory behind the model listers of providers serving the OpenAI `/models` shape.
 *
 * `llm4s-core` holds only the [[ProviderModelLister]] contract. This factory lived there too
 * until it was found to read a vendor field - OpenAI's `organization` - straight off
 * `NamedProviderConfig`; it moved here, with the OpenAI wire format, when that field became a
 * provider-specific key ([[https://github.com/llm4s/llm4s/issues/1133 #1133]]). Its package is
 * unchanged. A provider module that serves the same shape depends on `llm4s-openai-compatible`
 * and calls [[openAICompatible]]; one that does not implements [[ProviderModelLister]].
 */
@Stable
object ProviderModelListers:

  /**
   * A lister for any provider serving the OpenAI-compatible `/models` endpoint.
   *
   * Every request carries `Authorization: Bearer <apiKey>` when the section has a key, then
   * `sectionHeaders(section)`, then `extraHeaders`, then the section's own `headers`; a later
   * header of the same name wins.
   *
   *  @param provider       the `ProviderId` that owns the returned models; the config section must name it
   *  @param defaultBaseUrl base URL used when the section does not override it
   *  @param modelsPath     path of the listing endpoint, relative to the base URL
   *  @param extraHeaders   headers the provider requires on every request, e.g. OpenRouter's `X-Title`
   *  @param apiKeyRequired whether a section without an `apiKey` fails; `false` for endpoints,
   *                        such as a local server, that take unauthenticated requests
   *  @param sectionHeaders headers derived from the section being listed - typically from a
   *                        provider-specific key, as [[openAIOrganizationHeader]] sends OpenAI's
   *                        `organization`. None by default.
   */
  def openAICompatible(
    provider: ProviderId,
    defaultBaseUrl: String,
    modelsPath: String = "/models",
    extraHeaders: Map[String, String] = Map.empty,
    apiKeyRequired: Boolean = true,
    sectionHeaders: NamedProviderConfig => Map[String, String] = _ => Map.empty
  ): ProviderModelLister =
    new ProviderModelLister:
      def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
        for
          normalized <- config.requireProvider(provider)
          apiKey     <- if apiKeyRequired then normalized.requireApiKey.map(Some(_)) else Right(normalized.apiKey)
          baseUrl = normalized.baseUrlOrDefault(defaultBaseUrl)
          headers = requestHeaders(normalized, apiKey, sectionHeaders(normalized), extraHeaders)
          response <- httpClient
            .get(s"${baseUrl.asUrl}$modelsPath", headers = headers, timeout = 10.seconds)
            .mapServiceError(provider.asString, "Failed to discover models")
          okResponse <- response.ensureSuccess(provider.asString)
          json       <- readBody(okResponse.body)
          models     <- parseModels(json, provider)
        yield models

  /**
   * The section's `organization` key, as the `OpenAI-Organization` header - for a provider that
   * declares [[org.llm4s.llmconnect.config.OpenAIConfig.OrganizationKey]], passed as
   * `sectionHeaders`. Empty when the section sets none.
   */
  val openAIOrganizationHeader: NamedProviderConfig => Map[String, String] =
    section => section.extra(OpenAIConfig.OrganizationKey).map("OpenAI-Organization" -> _).toMap

  private def requestHeaders(
    config: NamedProviderConfig,
    apiKey: Option[ApiKey],
    fromSection: Map[String, String],
    extraHeaders: Map[String, String]
  ): Map[String, String] =
    apiKey.map(key => "Authorization" -> s"Bearer ${key.asKey}").toMap ++
      fromSection ++
      extraHeaders ++
      config.headers

  /**
   * The listing's body, read with `BoundedJson` so that a deeply nested one is refused before it
   * is parsed ([[https://github.com/llm4s/llm4s/issues/1658 #1658]]).
   */
  private def readBody(body: String): Result[ujson.Value] =
    BoundedJson
      .read(body)
      .left
      .map(err => ValidationError("responseBody", s"Failed to parse JSON response: ${err.message}"))

  /**
   * The `data` array's models. Every step uses an accessor that returns an `Option`: `json("data")`
   * on a non-object throws `ujson.Value.InvalidData`, whose message renders the whole value
   * recursively, and on a deeply nested top-level array that overflowed the stack (#1658).
   */
  private def parseModels(json: ujson.Value, provider: ProviderId): Result[List[DiscoveredModel]] =
    json.objOpt
      .flatMap(_.get("data"))
      .flatMap(_.arrOpt)
      .toRight(ValidationError("data", "Missing or invalid models payload: expected an object with a `data` array"))
      .map(_.toList.flatMap(parseModel(_, provider)))

  private def parseModel(json: ujson.Value, provider: ProviderId): Option[DiscoveredModel] =
    // An entry that is not an object, or has no id, is skipped rather than failing the listing.
    json.objOpt.flatMap(obj => obj.get("id").flatMap(_.strOpt).filter(_.nonEmpty).map(id => obj -> id)).map {
      (obj, id) =>
        val metadata =
          List(
            obj.get("created").flatMap(_.numOpt).map(n => "created" -> n.toLong.toString),
            obj.get("owned_by").flatMap(_.strOpt).map("ownedBy" -> _),
            obj.get("name").flatMap(_.strOpt).map("displayName" -> _),
            obj.get("description").flatMap(_.strOpt).map("description" -> _),
          ).flatten.toMap

        DiscoveredModel(ModelName(id), provider, metadata)
    }
