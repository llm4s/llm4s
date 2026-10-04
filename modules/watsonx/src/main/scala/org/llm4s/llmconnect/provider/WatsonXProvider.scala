package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, WatsonXConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for IBM watsonx.ai.
 *
 * A section names the model and the `projectId` (or `spaceId`) inference runs in, and takes the
 * IBM Cloud API key as `apiKey` - or from `llm4s.credentials.watsonx.apiKey`, which
 * `WATSONX_API_KEY` binds. `baseUrl` selects the region and defaults to Dallas (`us-south`).
 *
 * {{{
 * watsonx-main {
 *   provider  = "watsonx"
 *   model     = "ibm/granite-13b-instruct-v2"
 *   projectId = ${?WATSONX_PROJECT_ID}
 * }
 * }}}
 */
object WatsonXProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("watsonx")

  /** The key naming the watsonx project. */
  val ProjectIdKey: String = "projectId"

  /** The key naming a watsonx deployment space, used instead of a project. */
  val SpaceIdKey: String = "spaceId"

  /** The key giving the watsonx API version date. */
  val ApiVersionKey: String = "apiVersion"

  /** The key giving the IAM token endpoint. */
  val IamUrlKey: String = "iamUrl"

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some(WatsonXConfig.DEFAULT_BASE_URL),
    baseUrlExample = "e.g. https://eu-de.ml.cloud.ibm.com",
    baseUrlEnv = Some("WATSONX_BASE_URL"),
    apiKeyEnv = Seq("WATSONX_API_KEY"),
    extras = Seq(
      ProviderConfigKey.optional(ProjectIdKey, "the watsonx project ID (required unless spaceId is set)"),
      ProviderConfigKey.optional(SpaceIdKey, "a watsonx deployment space ID, used instead of the project"),
      ProviderConfigKey.optional(
        ApiVersionKey,
        "the watsonx API version date",
        default = Some(WatsonXConfig.DEFAULT_API_VERSION)
      ),
      ProviderConfigKey.optional(
        IamUrlKey,
        "the IBM Cloud IAM token endpoint",
        default = Some(WatsonXConfig.DEFAULT_IAM_URL)
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config <- WatsonXConfig.fromValues(
        modelName = section.model.asString,
        apiKey = apiKey,
        projectId = section.extra(ProjectIdKey),
        spaceId = section.extra(SpaceIdKey),
        baseUrl = baseUrl,
        apiVersion = section.extra(ApiVersionKey).getOrElse(WatsonXConfig.DEFAULT_API_VERSION),
        iamUrl = section.extra(IamUrlKey).getOrElse(WatsonXConfig.DEFAULT_IAM_URL)
      )
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[WatsonXConfig](id, config)
      .flatMap(WatsonXClient(_, options.metrics, options.exchangeLogging))
