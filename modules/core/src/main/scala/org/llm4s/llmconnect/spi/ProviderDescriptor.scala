package org.llm4s.llmconnect.spi

import org.llm4s.config.{ ProviderModelLister, ProvidersConfigModel }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import scala.reflect.ClassTag

/**
 * Everything `llm4s` needs to know about one LLM provider, supplied by the
 * provider itself.
 *
 * A descriptor is the whole extension point: implement it, register it (by
 * listing it in an [[Llm4sProviderModule]], or by handing it to
 * [[ProviderRegistry.of]]), and the provider becomes reachable from
 * configuration, validation, client construction and model discovery without
 * editing anything in `llm4s-core`. Before this existed, adding a provider
 * meant edits to roughly eight shared files — see
 * [[https://github.com/llm4s/llm4s/issues/1131 #1131]].
 *
 * @example
 * {{{
 * object BedrockProvider extends ProviderDescriptor:
 *   val id         = ProviderId("bedrock")
 *   val configSpec = ProviderConfigSpec(requiresApiKey = true, requiresEndpoint = true)
 *
 *   def buildConfig(providerName: String, section: NamedProviderConfig)(using
 *     ContextWindowResolver
 *   ): Result[ProviderConfig] = ...
 *
 *   def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
 *     ModelRegistryService
 *   ): Result[LLMClient] =
 *     ProviderDescriptor.expectConfig[BedrockConfig](id, config).flatMap(BedrockClient(_, options.metrics))
 * }}}
 */
trait ProviderDescriptor:

  /** Canonical id, e.g. `ProviderId("openai")`. Must be unique within a registry. */
  def id: ProviderId

  /**
   * Alternative spellings accepted in `provider = "..."`, folded onto [[id]].
   *
   * For example Gemini accepts `"google"`. Aliases are matched after the same
   * trim/lowercase canonicalisation as `id` itself.
   */
  def aliases: Set[String] = Set.empty

  /** Which config fields this provider requires, and its default endpoint. */
  def configSpec: ProviderConfigSpec

  /** What this provider's client implements. Defaults to the full interface. */
  def features: ProviderFeatures = ProviderFeatures.default

  /** Live model discovery, when the provider's API offers it. */
  def modelLister: Option[ProviderModelLister] = None

  /**
   * Turns a validated config section into the runtime `ProviderConfig` for
   * this provider.
   *
   * The section has already been checked against `configSpec`, so required
   * fields are present; a `Left` here means something `configSpec` cannot
   * express (a malformed value, say).
   *
   * @param providerName the user's instance name (`llm4s.providers.<name>`), for error messages.
   */
  def buildConfig(providerName: String, section: ProvidersConfigModel.NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig]

  /**
   * Constructs the client for a `ProviderConfig` this provider produced.
   *
   * `config` is whatever the caller passed, so implementations must check the
   * type rather than cast — `ProviderDescriptor.expectConfig` does that and
   * produces the standard mismatch error.
   */
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient]

object ProviderDescriptor:

  /**
   * Narrows a `ProviderConfig` to the subtype a provider's client needs.
   *
   * @return `Left` with a [[org.llm4s.error.ConfigurationError]] naming both the provider
   *         and the config type it was handed, when the config belongs to a different provider.
   */
  def expectConfig[C <: ProviderConfig](id: ProviderId, config: ProviderConfig)(using
    ct: ClassTag[C]
  ): Result[C] =
    config match
      case matched: C => Right(matched)
      case other =>
        Left(ConfigurationError(s"Invalid config type ${other.getClass.getSimpleName} for provider ${id.asString}"))

  /**
   * Reads a required field out of a config section, failing with the message
   * shape used across all providers.
   *
   * @param providerName the user's instance name (`llm4s.providers.<name>`).
   * @param fieldName    human-readable field name, e.g. `"api key"`.
   * @param hint         where to set it, e.g. `"llm4s.providers.<name>.apiKey"`.
   */
  def requireField[A](providerName: String, fieldName: String, value: Option[A], hint: String): Result[A] =
    value.toRight(ConfigurationError(s"Configured provider '$providerName' is missing $fieldName ($hint)"))

  /** Reads the `apiKey` that a `requiresApiKey` [[ProviderConfigSpec]] guarantees is present. */
  def requireApiKey(providerName: String, section: ProvidersConfigModel.NamedProviderConfig): Result[String] =
    requireField(providerName, "api key", section.apiKey.map(_.asKey), "llm4s.providers.<name>.apiKey")

  /**
   * The section's `baseUrl`, falling back to the spec's `defaultBaseUrl`.
   *
   * Fails only for a provider that has neither — Ollama, whose endpoint is
   * wherever the user happens to run it.
   */
  def resolveBaseUrl(
    providerName: String,
    section: ProvidersConfigModel.NamedProviderConfig,
    spec: ProviderConfigSpec
  ): Result[String] =
    requireField(
      providerName,
      "base URL",
      section.baseUrl.map(_.asUrl).orElse(spec.defaultBaseUrl),
      "llm4s.providers.<name>.baseUrl"
    )
