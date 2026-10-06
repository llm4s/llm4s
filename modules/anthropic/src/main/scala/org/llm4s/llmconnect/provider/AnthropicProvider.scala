package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ AnthropicConfigKeys, AnthropicModelLister, ProviderModelLister }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{ AnthropicConfig, AnthropicWorkloadIdentity, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Anthropic Claude API. */
@Stable
object AnthropicProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("anthropic")

  /** `auth` keys for Anthropic's workload identity federation. */
  val FederationRuleIdKey: String = "federationRuleId"
  val OrganizationIdKey: String   = "organizationId"
  val ServiceAccountIdKey: String = "serviceAccountId"
  val WorkspaceIdKey: String      = "workspaceId"

  /**
   * The key falls back to `llm4s.credentials.anthropic.apiKey`, bound to `ANTHROPIC_API_KEY`; a section with an
   * `auth` block uses workload identity federation instead and needs no key.
   */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(
        AnthropicConfig.DEFAULT_BASE_URL,
        Seq(AnthropicConfigKeys.ANTHROPIC_API_KEY)
      )
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(FederationRuleIdKey, "the Anthropic federation rule id (fdrl_...)"),
          ProviderConfigKey.required(OrganizationIdKey, "the Anthropic organization id"),
          ProviderConfigKey.optional(ServiceAccountIdKey, "the Anthropic service account id (svac_...)"),
          ProviderConfigKey.optional(WorkspaceIdKey, "the Anthropic workspace id (wrkspc_...)")
        )
      )

  override val modelLister: Option[ProviderModelLister] = Some(AnthropicModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      workloadIdentity <- workloadIdentityOf(providerName, section)
      apiKey <-
        if (workloadIdentity.isDefined) Right("") else ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- AnthropicConfig.fromValues(section.model.asString, apiKey, baseUrl, workloadIdentity)
    yield config

  private def workloadIdentityOf(
    providerName: String,
    section: NamedProviderConfig
  ): Result[Option[AnthropicWorkloadIdentity]] =
    section.auth match
      case None => Right(None)
      case Some(auth) =>
        for
          file <- auth.identityToken match
            case IdentitySource.File(path) => Right(path)
            case IdentitySource.Literal(_) =>
              Left(
                ConfigurationError(
                  s"llm4s.providers.$providerName.auth: the Anthropic SDK reads the identity token from a file; " +
                    "set identityTokenFile, not identityToken",
                  List("identityTokenFile")
                )
              )
          rule <- ProviderDescriptor.requireAuthExtra(providerName, auth, FederationRuleIdKey)
          org  <- ProviderDescriptor.requireAuthExtra(providerName, auth, OrganizationIdKey)
        yield Some(
          AnthropicWorkloadIdentity(file, rule, org, auth.extra(ServiceAccountIdKey), auth.extra(WorkspaceIdKey))
        )

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[AnthropicConfig](id, config)
      .flatMap(AnthropicClient(_, options.metrics, options.exchangeLogging))
