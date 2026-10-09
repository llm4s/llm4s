package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Explicit AWS credentials for [[BedrockConfig]].
 *
 * Temporary credentials (STS, SSO, an assumed role) need the `sessionToken` as well; without
 * it the signature is rejected.
 */
final case class BedrockCredentials(
  accessKeyId: String,
  secretAccessKey: String,
  sessionToken: Option[String] = None
) {
  override def toString: String =
    s"BedrockCredentials(accessKeyId=${Redaction.secret(accessKeyId)}, secretAccessKey=***, " +
      s"sessionToken=${sessionToken.map(_ => "***").getOrElse("none")})"
}

/**
 * Configuration for the AWS Bedrock Converse API.
 *
 * Authentication, in order of precedence: explicit [[credentials]]; the shared-config
 * [[profile]]; otherwise the AWS default credential chain (environment variables,
 * `~/.aws/credentials`, EC2 instance profile, ECS task role, ...).
 *
 * Build one with [[BedrockConfig.fromValues]], which validates it and resolves
 * `contextWindow` and `reserveCompletion` from the model registry.
 *
 * @param region            AWS region that hosts the model, e.g. `"us-east-1"`.
 * @param model             Bedrock model id or inference-profile id, e.g.
 *                          `"anthropic.claude-3-5-sonnet-20241022-v2:0"` or
 *                          `"us.anthropic.claude-3-5-sonnet-20241022-v2:0"`.
 * @param contextWindow     Total token capacity of the model (prompt plus completion).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param credentials       Explicit credentials; the default chain is used when absent.
 * @param profile           AWS shared-config profile name.
 * @param endpointUrl       Endpoint override, e.g. a VPC endpoint or a test server.
 * @param timeouts          how long a `Converse` call and a `ConverseStream` call may take: the section's
 *                          `timeouts` block. An absent value keeps the client's default: the AWS SDK's
 *                          own for a request, and no limit for a stream ([[ProviderTimeouts]]).
 */
final case class BedrockConfig(
  region: String,
  model: String,
  contextWindow: Int,
  reserveCompletion: Int,
  credentials: Option[BedrockCredentials] = None,
  profile: Option[String] = None,
  endpointUrl: Option[String] = None,
  override val timeouts: ProviderTimeouts = ProviderTimeouts.default
) extends ProviderConfig:
  override val providerId: ProviderId                                  = ProviderId("bedrock")
  override def withModel(model: String): BedrockConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): BedrockConfig = copy(timeouts = timeouts)

  override def toString: String =
    s"BedrockConfig(region=$region, model=$model, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion, " +
      s"credentials=${credentials.map(_.toString).getOrElse("(default chain)")}, " +
      s"profile=${profile.getOrElse("none")}, endpointUrl=${endpointUrl.getOrElse("default")}, timeouts=$timeouts)"

object BedrockConfig:

  /** Used when the model registry does not know the model. */
  private val DefaultContextWindow     = 8192
  private val DefaultReserveCompletion = 4096

  /**
   * Constructs a [[BedrockConfig]].
   *
   * Context window and completion reserve come from the model registry, which lists Bedrock's
   * regional inference-profile ids (`us.`, `eu.`, `apac.`, `global.`) as well as the base model
   * ids; a model it does not know gets conservative defaults.
   *
   * @return `Left(ConfigurationError)` for a blank region, an access key without a secret (or the
   *         reverse), a session token without credentials, or credentials together with a profile.
   */
  def fromValues(
    modelName: String,
    region: String,
    credentials: Option[BedrockCredentials] = None,
    profile: Option[String] = None,
    endpointUrl: Option[String] = None
  )(using resolver: ContextWindowResolver): Result[BedrockConfig] =
    for
      _ <- ProviderConfig.nonEmpty("Bedrock", "region", region)
      _ <- ProviderConfig.nonEmpty("Bedrock", "model", modelName)
      _ <- credentials.fold[Result[Unit]](Right(())) { c =>
        for
          _ <- ProviderConfig.nonEmpty("Bedrock", "accessKeyId", c.accessKeyId)
          _ <- ProviderConfig.nonEmpty("Bedrock", "secretAccessKey", c.secretAccessKey)
        yield ()
      }
      _ <- Either.cond(
        !(credentials.isDefined && profile.isDefined),
        (),
        ConfigurationError("Bedrock: set either explicit credentials or a profile, not both")
      )
    yield
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("bedrock_converse", "bedrock"),
        modelName = modelName,
        defaultContextWindow = DefaultContextWindow,
        defaultReserve = DefaultReserveCompletion,
        fallbackResolver = _ => (DefaultContextWindow, DefaultReserveCompletion)
      )
      BedrockConfig(
        region = region,
        model = modelName,
        contextWindow = cw,
        reserveCompletion = rc,
        credentials = credentials,
        profile = profile,
        endpointUrl = endpointUrl
      )
