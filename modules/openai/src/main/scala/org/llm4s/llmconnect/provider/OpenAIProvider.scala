package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAIConfigKeys, OpenAIModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig, OpenAIWorkloadIdentity, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the OpenAI API. */
@Stable
object OpenAIProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("openai")

  /**
   * The OpenAI API base URL used when a provider section sets no `baseUrl`.
   *
   * This was `DefaultConfig.DEFAULT_OPENAI_BASE_URL` in `llm4s-core` until the
   * provider moved to `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"

  /** `auth` keys for OpenAI's workload identity federation. */
  val IdentityProviderIdKey: String = "identityProviderId"
  val ServiceAccountIdKey: String   = "serviceAccountId"
  val ClientIdKey: String           = "clientId"

  /**
   * The key falls back to `llm4s.credentials.openai.apiKey`, bound to `OPENAI_API_KEY`; a section with an
   * `auth` block uses workload identity federation instead and needs no key.
   */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL, Seq(OpenAIConfigKeys.OPENAI_API_KEY))
      .withExtras(Seq(OpenAIConfig.OrganizationConfigKey))
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(IdentityProviderIdKey, "the OpenAI workload identity provider id"),
          ProviderConfigKey.required(ServiceAccountIdKey, "the OpenAI service account id"),
          ProviderConfigKey.optional(ClientIdKey, "the client id, if the identity provider requires one")
        )
      )

  override val modelLister: Option[ProviderModelLister] = Some(OpenAIModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      workloadIdentity <- workloadIdentityOf(providerName, section)
      apiKey <-
        if (workloadIdentity.isDefined) Right("") else ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config <- OpenAIConfig.fromValues(
        section.model.asString,
        apiKey,
        section.extra(OpenAIConfig.OrganizationKey),
        baseUrl,
        workloadIdentity = workloadIdentity
      )
    yield config

  private def workloadIdentityOf(
    providerName: String,
    section: NamedProviderConfig
  ): Result[Option[OpenAIWorkloadIdentity]] =
    section.auth match
      case None => Right(None)
      case Some(auth) =>
        for
          idp <- ProviderDescriptor.requireAuthExtra(providerName, auth, IdentityProviderIdKey)
          sa  <- ProviderDescriptor.requireAuthExtra(providerName, auth, ServiceAccountIdKey)
        yield Some(OpenAIWorkloadIdentity(auth.identityToken, idp, sa, auth.extra(ClientIdKey)))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAIConfig](id, config)
      .flatMap(OpenAIClient(_, options.metrics, options.exchangeLogging))
