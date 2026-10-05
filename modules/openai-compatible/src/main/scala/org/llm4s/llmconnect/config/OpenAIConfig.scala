package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.spi.ProviderConfigKey
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the OpenAI API and providers that implement the
 * OpenAI-compatible REST interface.
 *
 * `baseUrl` governs which backend is contacted: `"https://api.openai.com/v1"`
 * reaches OpenAI directly, while a URL containing `"openrouter.ai"` causes
 * [[org.llm4s.llmconnect.LLMConnect]] to route to OpenRouter. Azure OpenAI
 * uses `AzureConfig`, not this class.
 *
 * This config lives in `llm4s-openai-compatible`, with OpenRouter, and
 * `llm4s-openai` - whose OpenAI and Requesty providers build it - depends on
 * that module for it ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 * Its package is unchanged from when it was in `llm4s-core`.
 *
 * Prefer [[OpenAIConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` from the model name automatically.
 *
 * @param apiKey        OpenAI API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"gpt-4o"`.
 * @param organization  Optional OpenAI organisation ID.
 * @param baseUrl       API base URL; determines provider routing in
 *                      [[org.llm4s.llmconnect.LLMConnect]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param explicitProviderId the provider this config belongs to, when a descriptor says so:
 *                      `requesty` for Requesty and `openrouter` for OpenRouter. `None` - what a
 *                      config built by hand gets - infers it from `baseUrl`; see [[providerId]].
 */
@Stable
case class OpenAIConfig(
  apiKey: String,
  model: String,
  organization: Option[String],
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  explicitProviderId: Option[ProviderId] = None
) extends ProviderConfig:
  /**
   * The provider this config belongs to: [[explicitProviderId]] when set, otherwise `openai`,
   * or `openrouter` when `baseUrl` points at OpenRouter.
   *
   * OpenAI, OpenRouter and Requesty all use this config, and `LLMConnect` routes a config to a
   * client by this id. The Requesty and OpenRouter descriptors set it explicitly, so a Requesty
   * config reports `requesty` - it used to report `openai`, because its base URL is neither
   * OpenAI's nor OpenRouter's - and an OpenRouter section with a proxy `baseUrl` still reaches
   * OpenRouter. The inference from `baseUrl` remains for a config built by hand and for
   * `provider = "openai"`, so an OpenAI section pointed at `openrouter.ai` reaches OpenRouter as
   * it did before the provider registry (#1131).
   */
  override def providerId: ProviderId =
    explicitProviderId.getOrElse(
      if baseUrl.contains("openrouter.ai") then ProviderId("openrouter") else ProviderId("openai")
    )

  override def endpointUrl: Option[String]            = Some(baseUrl)
  override def withModel(model: String): OpenAIConfig = copy(model = model)
  override def toString: String =
    s"OpenAIConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, organization=$organization, baseUrl=$baseUrl, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, providerId=${providerId.asString})"

object OpenAIConfig {
  private val standardReserve = 4096

  /**
   * The provider-specific key naming the OpenAI organisation ID, `organization`.
   *
   * It was a field of `NamedProviderConfig` that every provider carried; it is now declared only
   * by the providers that build an `OpenAIConfig` - OpenAI, Requesty and OpenRouter - and read
   * with `section.extra(OrganizationKey)` (#1133).
   */
  val OrganizationKey: String = "organization"

  /** The declaration of [[OrganizationKey]] each of those providers lists in its `ProviderConfigSpec.extras`. */
  val OrganizationConfigKey: ProviderConfigKey =
    ProviderConfigKey.optional(OrganizationKey, "the OpenAI organization ID, sent as the OpenAI-Organization header")

  private def openAIFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  /**
   * Constructs an [[OpenAIConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * The resolver first consults a bundled model-metadata catalogue; if the
   * model is not listed there it falls back to name-pattern matching before
   * defaulting to 8192 tokens. Prefer this factory over the primary
   * constructor so that new models receive correct context-window values
   * without manual lookup.
   *
   * @param modelName    Model identifier, e.g. `"gpt-4o"`.
   * @param apiKey       OpenAI API key; must be non-empty.
   * @param organization Optional OpenAI organisation ID.
   * @param baseUrl      API base URL; must be non-empty. Pass a URL containing
   *                     `"openrouter.ai"` to route through OpenRouter.
   * @param providerId   the provider the config belongs to, e.g. `ProviderId("requesty")`;
   *                     `None` infers it from `baseUrl`, as [[OpenAIConfig.providerId]] describes.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    organization: Option[String],
    baseUrl: String,
    providerId: Option[ProviderId] = None
  )(using resolver: ContextWindowResolver): Result[OpenAIConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("OpenAI", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("OpenAI", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("openai"),
        modelName = modelName,
        defaultContextWindow = 8192,
        defaultReserve = standardReserve,
        fallbackResolver = openAIFallback
      )
      OpenAIConfig(
        apiKey = apiKey,
        model = modelName,
        organization = organization,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc,
        explicitProviderId = providerId
      )
    }
}
