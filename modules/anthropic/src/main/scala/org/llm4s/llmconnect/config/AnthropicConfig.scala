package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.TokenExchange
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Anthropic Claude API.
 *
 * Prefer [[AnthropicConfig.fromValues]] over the primary constructor; it
 * resolves `contextWindow` and `reserveCompletion` automatically from the
 * model name.
 *
 * @param apiKey        Anthropic API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
 * @param baseUrl       API base URL; defaults to [[AnthropicConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param workloadIdentity  workload identity federation instead of `apiKey`, which is then empty. The SDK
 *                          posts the identity token to `<baseUrl>/v1/oauth/token` and the access token it gets back
 *                          to every request, so `baseUrl` must be `https://api.anthropic.com` (plain `http`
 *                          only to a loopback host, for tests). [[AnthropicConfig.fromValues]]
 *                          checks this, and `AnthropicClient` refuses a config built any other way that
 *                          breaks it.
 */
@Stable
case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  workloadIdentity: Option[AnthropicWorkloadIdentity] = None
) extends ProviderConfig:
  override val providerId: ProviderId                    = ProviderId("anthropic")
  override def endpointUrl: Option[String]               = Some(baseUrl)
  override def withModel(model: String): AnthropicConfig = copy(model = model)
  override def toString: String =
    s"AnthropicConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion, workloadIdentity=$workloadIdentity)"

object AnthropicConfig {

  /**
   * The Anthropic API base URL used when a named provider section sets no `baseUrl`.
   *
   * This was `DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` in `llm4s-core` until the
   * provider moved to `llm4s-anthropic` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_BASE_URL: String = "https://api.anthropic.com"

  private val standardReserve = 4096

  private def anthropicFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("claude-3")       => (200000, standardReserve)
      case name if name.contains("claude-3.5")     => (200000, standardReserve)
      case name if name.contains("claude-instant") => (100000, standardReserve)
      case _                                       => (200000, standardReserve)
    }

  /**
   * The hosts a config with `workloadIdentity` may target. Anthropic documents its federation token
   * endpoint only at `https://api.anthropic.com/v1/oauth/token`
   * ([[https://platform.claude.com/docs/en/manage-claude/wif-reference WIF reference]]), and the SDK
   * knows no other host. A gateway or proxy `baseUrl` stays available with an `apiKey`; with workload
   * identity it would receive both the identity token and the Anthropic access token minted from it.
   */
  private[llm4s] val WorkloadIdentityHosts: Set[String] = Set("api.anthropic.com")

  /**
   * The rules every [[AnthropicConfig]] must meet, whichever way it was built: [[fromValues]] applies
   * them, and `AnthropicClient` applies them again to a config built with the constructor or `copy`.
   * Without `workloadIdentity`, `apiKey` must not be blank: it is the only credential. With
   * `workloadIdentity` set, `apiKey` must be empty (a config authenticates one way) and
   * `baseUrl` must be `https` to one of [[WorkloadIdentityHosts]] - or a loopback host, for tests -
   * since the SDK posts the identity token to `<baseUrl>/v1/oauth/token` and sends the access token
   * it gets back with every request.
   */
  private[llm4s] def validate(config: AnthropicConfig): Result[AnthropicConfig] =
    config.workloadIdentity match
      // Without workload identity the key is the only credential: a blank one would authenticate as nobody.
      case None => ProviderConfig.nonEmpty("Anthropic", "apiKey", config.apiKey).map(_ => config)
      case Some(_) =>
        for
          _ <- Either.cond(
            config.apiKey.trim.isEmpty,
            (),
            ConfigurationError(
              "Anthropic config sets both apiKey and workloadIdentity; a config authenticates one way - use one",
              List("apiKey", "workloadIdentity")
            )
          )
          _ <- TokenExchange.requireTrustedHost(config.baseUrl, "baseUrl", "Anthropic", WorkloadIdentityHosts)
        yield config

  /**
   * Constructs an [[AnthropicConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
   * @param apiKey    Anthropic API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty, and `https://api.anthropic.com` (or `http` to a loopback
   *                  host) when `workloadIdentity` is set.
   * @param workloadIdentity workload identity federation; `apiKey` must then be empty.
   * @return `Left(ConfigurationError)` for a blank field or a config [[validate]] refuses.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String,
    workloadIdentity: Option[AnthropicWorkloadIdentity] = None
  )(using resolver: ContextWindowResolver): Result[AnthropicConfig] =
    (for {
      _ <- if (workloadIdentity.isDefined) Right(()) else ProviderConfig.nonEmpty("Anthropic", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Anthropic", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("anthropic"),
        modelName = modelName,
        defaultContextWindow = 200000,
        defaultReserve = standardReserve,
        fallbackResolver = anthropicFallback
      )
      AnthropicConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc,
        workloadIdentity = workloadIdentity
      )
    }).flatMap(validate)
}
