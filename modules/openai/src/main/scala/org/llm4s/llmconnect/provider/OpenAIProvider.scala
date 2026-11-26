package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.annotation.Stable
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.config.NamedProviderConfig
import org.llm4s.config.{ OpenAIConfigKeys, OpenAIModelLister, ProviderModelLister }
import org.llm4s.llmconnect.auth.AuthConfig
import org.llm4s.llmconnect.config.{
  ContextWindowResolver,
  OpenAIApi,
  OpenAIConfig,
  OpenAIReasoningSummary,
  OpenAIWorkloadIdentity,
  ProviderConfig
}
import org.llm4s.llmconnect.spi.{
  ProviderBuildContext,
  ProviderConfigContext,
  ProviderConfigKey,
  ProviderConfigSpec,
  ProviderDescriptor
}
import org.llm4s.llmconnect.LLMClient
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
  val DEFAULT_BASE_URL: BaseUrl = BaseUrl("https://api.openai.com/v1")

  /** `auth` keys for OpenAI's workload identity federation. */
  private[llm4s] val IdentityProviderIdKey: String = "identityProviderId"
  private[llm4s] val ServiceAccountIdKey: String   = "serviceAccountId"
  private[llm4s] val ClientIdKey: String           = "clientId"

  /** Each `workloadIdentity` field a config refusal names, as the `auth` key the section spells it with. */
  private val AuthSectionKeys: Map[String, String] =
    (Seq(IdentityProviderIdKey, ServiceAccountIdKey, ClientIdKey) ++ AuthConfig.ReservedKeys)
      .map(key => s"${OpenAIWorkloadIdentity.FieldPrefix}$key" -> s"auth.$key")
      .toMap

  /**
   * The key falls back to `llm4s.credentials.openai.apiKey`, bound to `OPENAI_API_KEY`; a section with an
   * `auth` block uses workload identity federation instead and needs no key.
   */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL, Seq(OpenAIConfigKeys.OPENAI_API_KEY))
      .withExtras(
        Seq(OpenAIConfig.OrganizationConfigKey, OpenAIConfig.ApiConfigKey, OpenAIConfig.ReasoningSummaryConfigKey)
      )
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(IdentityProviderIdKey, "the OpenAI workload identity provider id"),
          ProviderConfigKey.required(ServiceAccountIdKey, "the OpenAI service account id"),
          ProviderConfigKey.optional(ClientIdKey, "the client id, if the identity provider requires one")
        )
      )

  override val modelLister: Option[ProviderModelLister] = Some(OpenAIModelLister)

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows
    for
      workloadIdentity <- workloadIdentityOf(context.providerName, section)
      apiKey <-
        if (workloadIdentity.isDefined) Right(Secret(""))
        else ProviderDescriptor.requireApiKey(context.providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(context.providerName, section, configSpec)
      api     <- optionalExtra(context.providerName, section, OpenAIConfig.ApiKey)(OpenAIApi.parse)
      summary <- optionalExtra(context.providerName, section, OpenAIConfig.ReasoningSummaryKey)(
        OpenAIReasoningSummary.parse
      )
      config <- OpenAIConfig
        .fromValues(
          section.model,
          apiKey,
          baseUrl,
          section.extra(OpenAIConfig.OrganizationKey),
          workloadIdentity = workloadIdentity
        )
        .left
        .map(ProviderConfig.inSection(context.providerName, AuthSectionKeys))
    yield config.withApi(api).withReasoningSummary(summary)

  /** An optional extra `key` of the section, parsed; a value `parse` refuses is an error naming the section. */
  private def optionalExtra[A](providerName: String, section: NamedProviderConfig, key: String)(
    parse: String => Result[A]
  ): Result[Option[A]] =
    section.extra(key) match
      case None => Right(None)
      case Some(value) =>
        parse(value).map(Some(_)).left.map(ProviderConfig.inSection(providerName))

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
        yield Some(OpenAIWorkloadIdentity(auth.identityToken, idp, sa, auth.extra(ClientIdKey).map(Secret(_))))

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry
    ProviderDescriptor
      .expectConfig[OpenAIConfig](id, config)
      .flatMap(OpenAIClient(_, context.options))
