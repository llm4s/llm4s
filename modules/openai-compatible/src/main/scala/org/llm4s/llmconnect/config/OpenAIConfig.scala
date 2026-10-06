package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.TokenExchange
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
  explicitProviderId: Option[ProviderId] = None,
  workloadIdentity: Option[OpenAIWorkloadIdentity] = None
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
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, providerId=${providerId.asString}, " +
      s"workloadIdentity=$workloadIdentity)"

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
   * The OpenAI API hosts a config with `workloadIdentity` may target: the global API and the
   * data-residency regions, as listed by the OpenAI Java SDK's `com.openai.core.DataResidency`
   * (`GLOBAL`, `US`, `EU`, `AE`).
   */
  private[llm4s] val WorkloadIdentityHosts: Set[String] =
    Set("api.openai.com", "us.api.openai.com", "eu.api.openai.com", "ae.api.openai.com")

  /**
   * The rules every [[OpenAIConfig]] must meet, whichever way it was built: [[fromValues]] applies
   * them, and `OpenAIClient` applies them again to a config built with the constructor or `copy`.
   * With `workloadIdentity` set, `apiKey` must be empty (a config authenticates one way), the
   * config must belong to `openai`, and `baseUrl` must be `https` to one of [[WorkloadIdentityHosts]]
   * (or a loopback host, for tests): the token OpenAI's exchange issues is an OpenAI credential, sent
   * as the bearer of every request to `baseUrl`, so a Requesty, OpenRouter, proxy or other custom
   * endpoint would receive it. The provider label alone is not enough - an `openai` config with a
   * custom `baseUrl` still reports `openai` - so the host is checked against an allow-list.
   */
  private[llm4s] def validate(config: OpenAIConfig): Result[OpenAIConfig] =
    config.workloadIdentity match
      case None => Right(config)
      case Some(_) =>
        for
          _ <- Either.cond(
            config.apiKey.trim.isEmpty,
            (),
            ConfigurationError(
              "OpenAI config sets both apiKey and workloadIdentity; a config authenticates one way - use one",
              List("apiKey", "workloadIdentity")
            )
          )
          _ <- Either.cond(
            config.providerId == ProviderId("openai"),
            (),
            ConfigurationError(
              s"OpenAI workload identity is for provider openai only, but this config belongs to " +
                s"${config.providerId.asString}: the exchanged token is an OpenAI credential",
              List("workloadIdentity")
            )
          )
          _ <- TokenExchange.requireTrustedHost(config.baseUrl, "baseUrl", "OpenAI", WorkloadIdentityHosts)
        yield config

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
   * @param apiKey       OpenAI API key; must be non-empty, except that it is empty exactly when
   *                     `workloadIdentity` is set.
   * @param organization Optional OpenAI organisation ID.
   * @param baseUrl      API base URL; must be non-empty. Pass a URL containing
   *                     `"openrouter.ai"` to route through OpenRouter.
   * @param providerId   the provider the config belongs to, e.g. `ProviderId("requesty")`;
   *                     `None` infers it from `baseUrl`, as [[OpenAIConfig.providerId]] describes.
   * @param workloadIdentity OpenAI workload identity federation instead of `apiKey`; only for a
   *                     config belonging to `openai` whose `baseUrl` is an OpenAI API host
   *                     (`https://api.openai.com/v1` or a data-residency region such as
   *                     `https://eu.api.openai.com/v1`).
   * @return `Left(ConfigurationError)` for a blank field or a config [[validate]] refuses.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    organization: Option[String],
    baseUrl: String,
    providerId: Option[ProviderId] = None,
    workloadIdentity: Option[OpenAIWorkloadIdentity] = None
  )(using resolver: ContextWindowResolver): Result[OpenAIConfig] =
    (for {
      _ <- if (workloadIdentity.isDefined) Right(()) else ProviderConfig.nonEmpty("OpenAI", "apiKey", apiKey)
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
        explicitProviderId = providerId,
        workloadIdentity = workloadIdentity
      )
    }).flatMap(validate)
}
