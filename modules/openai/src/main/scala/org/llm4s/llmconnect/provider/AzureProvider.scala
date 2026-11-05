package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.OpenAIConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Azure OpenAI deployments.
 *
 * Azure reuses `OpenAIClient` but not `OpenAIConfig`: its endpoint is
 * per-deployment and it requires an `apiVersion`, which `AzureConfig` carries.
 *
 * Both are provider-specific keys of the section - `endpoint` (required) and `apiVersion`
 * (default [[org.llm4s.llmconnect.config.AzureConfig.DEFAULT_API_VERSION]]) - rather than fields of `NamedProviderConfig`,
 * which they were until [[https://github.com/llm4s/llm4s/issues/1133 #1133]]; the HOCON is
 * unchanged:
 *
 * {{{
 * azure-main {
 *   provider   = "azure"
 *   model      = "gpt-4o"
 *   endpoint   = "https://my-resource.openai.azure.com/"
 *   apiVersion = "2025-01-01-preview"
 * }
 * }}}
 *
 * A resource that forbids keys authenticates with Microsoft Entra ID instead (#2274): `authentication =
 * "entra-id"` takes tokens from Azure's `DefaultAzureCredential` - a managed identity, workload identity,
 * the Azure CLI's login - through `llm4s-azure-identity`, which must then be on the classpath. Such a
 * section takes no `apiKey`, its own or the shared `AZURE_OPENAI_API_KEY`; `clientId` picks a
 * user-assigned managed identity:
 *
 * {{{
 * azure-prod {
 *   provider       = "azure"
 *   model          = "gpt-4o"
 *   endpoint       = "https://my-resource.openai.azure.com"
 *   authentication = "entra-id"
 *   clientId       = ${?AZURE_CLIENT_ID}
 * }
 * }}}
 */
@Stable
object AzureProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("azure")

  /** The key naming the Azure OpenAI deployment endpoint. */
  val EndpointKey: String = "endpoint"

  /** The key naming the Azure OpenAI API version. */
  val ApiVersionKey: String = "apiVersion"

  /** The key choosing how a section authenticates: [[ApiKeyAuthentication]] (the default) or [[EntraIdAuthentication]]. */
  val AuthenticationKey: String = "authentication"

  /** The client id of the user-assigned managed identity (or workload identity) Entra ID authentication uses. */
  val ClientIdKey: String = "clientId"

  /** `authentication = "api-key"`: the section's `apiKey`, or the shared `AZURE_OPENAI_API_KEY`. */
  val ApiKeyAuthentication: String = "api-key"

  /** `authentication = "entra-id"`: Microsoft Entra ID tokens from `llm4s-azure-identity`, no API key. */
  val EntraIdAuthentication: String = "entra-id"

  /**
   * The key falls back to `llm4s.credentials.azure.apiKey`, bound to `AZURE_OPENAI_API_KEY`; a section with
   * `authentication = "entra-id"` needs none.
   */
  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    apiKeyEnv = Seq(OpenAIConfigKeys.AZURE_OPENAI_API_KEY),
    keylessWhen = Some(AuthenticationKey -> EntraIdAuthentication),
    extras = Seq(
      ProviderConfigKey(
        EndpointKey,
        "the model endpoint/deployment name in your Azure OpenAI resource",
        required = true,
        env = Some(OpenAIConfigKeys.AZURE_API_BASE)
      ),
      ProviderConfigKey(
        ApiVersionKey,
        "the Azure OpenAI API version",
        default = Some(AzureConfig.DEFAULT_API_VERSION),
        env = Some(OpenAIConfigKeys.AZURE_API_VERSION)
      ),
      ProviderConfigKey.optional(
        AuthenticationKey,
        s"how the section authenticates: $ApiKeyAuthentication, or $EntraIdAuthentication for Microsoft Entra ID " +
          "(needs llm4s-azure-identity)",
        default = Some(ApiKeyAuthentication)
      ),
      ProviderConfigKey.optional(
        ClientIdKey,
        s"the client id of the user-assigned managed identity to use with authentication = $EntraIdAuthentication"
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      endpoint <- ProviderDescriptor.requireExtra(providerName, section, EndpointKey)
      mode     <- authentication(providerName, section)
      apiVersion = section.extra(ApiVersionKey).getOrElse(AzureConfig.DEFAULT_API_VERSION)
      model      = section.model.asString
      clientId   = section.extra(ClientIdKey).map(_.trim).filter(_.nonEmpty)
      config <-
        if mode == EntraIdAuthentication then
          AzureEntraIdSupport
            .tokenProvider(clientId)
            .flatMap(AzureConfig.fromTokenProvider(model, endpoint, _, apiVersion))
        else if clientId.isDefined then
          Left(
            ConfigurationError(
              s"llm4s.providers.$providerName.$ClientIdKey applies only with " +
                s"$AuthenticationKey = \"$EntraIdAuthentication\"; remove it, or set $AuthenticationKey"
            )
          )
        else
          ProviderDescriptor
            .requireApiKey(providerName, section)
            .flatMap(AzureConfig.fromValues(model, endpoint, _, apiVersion))
    yield config

  /** The section's `authentication`, normalised: [[ApiKeyAuthentication]] or [[EntraIdAuthentication]]. */
  private def authentication(providerName: String, section: NamedProviderConfig): Result[String] =
    section.extra(AuthenticationKey).map(_.trim.toLowerCase(java.util.Locale.ROOT)) match
      case None | Some(ApiKeyAuthentication) => Right(ApiKeyAuthentication)
      case Some(EntraIdAuthentication)       => Right(EntraIdAuthentication)
      case Some(other) =>
        Left(
          ConfigurationError(
            s"llm4s.providers.$providerName.$AuthenticationKey is '$other'; it must be " +
              s"\"$ApiKeyAuthentication\" or \"$EntraIdAuthentication\""
          )
        )

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[AzureConfig](id, config)
      .flatMap(OpenAIClient(_, options.metrics, options.exchangeLogging))
