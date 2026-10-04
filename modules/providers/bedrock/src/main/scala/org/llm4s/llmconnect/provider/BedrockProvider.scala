package org.llm4s.llmconnect.provider

import org.llm4s.config.BedrockConfigKeys.*
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for AWS Bedrock, through the Converse and ConverseStream APIs.
 *
 * A section names the AWS `region` (required, never defaulted: a model is only available in
 * some regions, and a silent default would send traffic to the wrong one). With no credentials it
 * authenticates through the AWS default credential chain; it can instead name a `profile`, or set
 * `accessKeyId` and `secretAccessKey` (plus `sessionToken` for temporary credentials). Bedrock has
 * no single API key, so there is no `llm4s.credentials.bedrock` block.
 *
 * {{{
 * claude-bedrock {
 *   provider = "bedrock"
 *   model    = "anthropic.claude-3-5-sonnet-20241022-v2:0"
 *   region   = ${?AWS_REGION}
 * }
 * }}}
 *
 * `baseUrl` is an optional endpoint override (a VPC endpoint, or a test server).
 */
object BedrockProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("bedrock")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    extras = Seq(
      ProviderConfigKey(
        REGION_KEY,
        "the AWS region that hosts the model, e.g. us-east-1",
        required = true,
        env = Some(AWS_REGION)
      ),
      ProviderConfigKey
        .optional(PROFILE_KEY, "the AWS shared-config profile to authenticate with")
        .copy(env = Some(AWS_PROFILE)),
      ProviderConfigKey
        .optional(ACCESS_KEY_ID_KEY, "an explicit AWS access key id (needs secretAccessKey)")
        .copy(env = Some(AWS_ACCESS_KEY_ID)),
      ProviderConfigKey
        .optional(SECRET_ACCESS_KEY_KEY, "the AWS secret access key that goes with accessKeyId")
        .copy(env = Some(AWS_SECRET_ACCESS_KEY)),
      ProviderConfigKey
        .optional(SESSION_TOKEN_KEY, "the session token for temporary AWS credentials")
        .copy(env = Some(AWS_SESSION_TOKEN))
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      region      <- ProviderDescriptor.requireExtra(providerName, section, REGION_KEY)
      credentials <- credentialsOf(providerName, section)
      config <- BedrockConfig.fromValues(
        modelName = section.model.asString,
        region = region,
        credentials = credentials,
        profile = section.extra(PROFILE_KEY),
        endpointUrl = section.baseUrl.map(_.asUrl)
      )
    yield config

  /** Both halves of a static credential pair, or neither; a lone half is a mistake worth naming. */
  private def credentialsOf(providerName: String, section: NamedProviderConfig): Result[Option[BedrockCredentials]] =
    (section.extra(ACCESS_KEY_ID_KEY), section.extra(SECRET_ACCESS_KEY_KEY), section.extra(SESSION_TOKEN_KEY)) match
      case (Some(keyId), Some(secret), token) => Right(Some(BedrockCredentials(keyId, secret, token)))
      case (None, None, None)                 => Right(None)
      case (None, None, Some(_)) =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName' sets $SESSION_TOKEN_KEY without $ACCESS_KEY_ID_KEY and $SECRET_ACCESS_KEY_KEY"
          )
        )
      case _ =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName' must set $ACCESS_KEY_ID_KEY and $SECRET_ACCESS_KEY_KEY together"
          )
        )

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[BedrockConfig](id, config)
      .flatMap(BedrockClient(_, options.metrics, options.exchangeLogging))
