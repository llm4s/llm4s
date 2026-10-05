package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAICompatibleConfigKeys, OpenAICompatibleModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAICompatibleConfig, ProviderConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.TokenExchangeConfig
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.Locale

/**
 * Registration for the generic `openai-compatible` provider: any endpoint
 * speaking the OpenAI `/chat/completions` API, with no module and no code.
 *
 * A named provider section with `provider = "openai-compatible"` needs a
 * `baseUrl` and a `model`; `apiKey` and `headers` are optional (local servers need
 * no key). It also accepts three provider-specific keys: `contextWindow` and
 * `reserveCompletion` (whole numbers; the model's window cannot be known from an
 * arbitrary endpoint, so these default to [[org.llm4s.llmconnect.config.OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW]]
 * and a quarter of the window up to [[org.llm4s.llmconnect.config.OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION]]),
 * and `streamUsage` (default `true`), which controls whether a streaming
 * request asks for token usage with `stream_options.include_usage`; set it to
 * `false` for a server that rejects that field. Several sections can use it side
 * by side:
 *
 * {{{
 * llm4s.providers {
 *   databricks {
 *     provider = "openai-compatible"
 *     baseUrl  = "https://ws.cloud.databricks.com/serving-endpoints"
 *     model    = "databricks-meta-llama-3-3-70b-instruct"
 *     auth {   // workload identity: no apiKey
 *       identityTokenFile = "/var/run/secrets/spiffe/svid.jwt"
 *       tokenUrl          = "https://ws.cloud.databricks.com/oidc/v1/token"
 *       clientId          = "<service principal UUID>"
 *       scope             = "all-apis"
 *     }
 *   }
 *   groq-main {
 *     provider = "openai-compatible"
 *     baseUrl  = "https://api.groq.com/openai/v1"
 *     model    = "openai/gpt-oss-120b"
 *     apiKey   = ${?GROQ_API_KEY}
 *     contextWindow = 131072
 *   }
 *   local-vllm {
 *     provider = "openai-compatible"
 *     baseUrl  = "http://localhost:8000/v1"
 *     model    = "Qwen/Qwen2.5-7B-Instruct"
 *   }
 * }
 * }}}
 *
 * The client is [[OpenAICompatibleClient]] with the standard dialect: no
 * reasoning parameters and no provider-specific decoding. A provider that needs
 * those gets its own [[OpenAICompatibleDialect]] and descriptor in this module.
 */
@Stable
object OpenAICompatibleProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId(OpenAICompatibleConfig.ProviderIdName)

  /** The key that turns `stream_options.include_usage` off for a server that rejects it. */
  val StreamUsageKey: String = "streamUsage"

  /**
   * The key giving the model's context window. It was a field of `NamedProviderConfig`, read by
   * this provider alone, until [[https://github.com/llm4s/llm4s/issues/1133 #1133]].
   */
  val ContextWindowKey: String = "contextWindow"

  /** The key giving the tokens held back for the reply; a field of `NamedProviderConfig` until #1133. */
  val ReserveCompletionKey: String = "reserveCompletion"

  /** `auth` keys: the RFC 8693 token endpoint (required), and the optional exchange parameters. */
  val TokenUrlKey: String = "tokenUrl"
  val ClientIdKey: String = "clientId"
  val ScopeKey: String    = "scope"
  val AudienceKey: String = "audience"

  // `baseUrlEnv` makes a missing-baseUrl error show `baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}`,
  // the binding that reads the conventional variable. The generic provider has no vendor, so no
  // `llm4s.credentials` block binds its key either: a section sets its own `apiKey`, if any.
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec(
      requiresBaseUrl = true,
      baseUrlExample = "e.g. http://localhost:8000/v1",
      baseUrlEnv = Some(OpenAICompatibleConfigKeys.OPENAI_COMPATIBLE_BASE_URL),
      extras = Seq(
        ProviderConfigKey.optional(
          ContextWindowKey,
          s"the model's context window in tokens, a positive whole number " +
            s"(default ${OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW})"
        ),
        ProviderConfigKey.optional(
          ReserveCompletionKey,
          "the tokens held back from the prompt for the reply, a whole number less than contextWindow"
        ),
        ProviderConfigKey.optional(
          StreamUsageKey,
          "whether a streaming request asks for token usage (stream_options.include_usage); " +
            "false for a server that rejects the field",
          default = Some("true")
        )
      )
    ).withAuthExtras(
      Seq(
        ProviderConfigKey.required(
          TokenUrlKey,
          "the RFC 8693 token endpoint, e.g. https://<workspace>/oidc/v1/token for Databricks"
        ),
        ProviderConfigKey.optional(
          ClientIdKey,
          "the client id sent with the exchange, e.g. a Databricks service principal's UUID"
        ),
        ProviderConfigKey.optional(ScopeKey, "the scope requested, e.g. all-apis for Databricks"),
        ProviderConfigKey.optional(AudienceKey, "the RFC 8693 audience parameter, if the token endpoint needs one")
      )
    )

  override val modelLister: Option[ProviderModelLister] = Some(OpenAICompatibleModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      baseUrl           <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      streamUsage       <- parseStreamUsage(providerName, section.extra(StreamUsageKey))
      contextWindow     <- parseCount(providerName, section, ContextWindowKey, min = 1, "a positive whole number")
      reserveCompletion <- parseCount(providerName, section, ReserveCompletionKey, min = 0, "a whole number, 0 or more")
      tokenExchange     <- tokenExchangeOf(providerName, section)
      config <- OpenAICompatibleConfig.fromValues(
        model = section.model.asString,
        baseUrl = baseUrl,
        apiKey = section.apiKey.map(_.asKey),
        contextWindow = contextWindow,
        reserveCompletion = reserveCompletion,
        headers = section.headers,
        streamUsage = streamUsage,
        tokenExchange = tokenExchange
      )
    yield config

  /** The exchange a section's `auth` block describes, if it has one. */
  private[llm4s] def tokenExchangeOf(
    providerName: String,
    section: NamedProviderConfig
  ): Result[Option[TokenExchangeConfig]] =
    section.auth match
      case None => Right(None)
      case Some(auth) =>
        ProviderDescriptor.requireAuthExtra(providerName, auth, TokenUrlKey).map { tokenUrl =>
          Some(
            TokenExchangeConfig(
              auth.identityToken,
              tokenUrl,
              auth.extra(ClientIdKey),
              auth.extra(ScopeKey),
              auth.extra(AudienceKey)
            )
          )
        }

  // Extras arrive as strings, so a number written in HOCON arrives as its text. Range checks
  // between the two counts (the reserve must be less than the window) are `fromValues`'s.
  private def parseCount(
    providerName: String,
    section: NamedProviderConfig,
    key: String,
    min: Int,
    expected: String
  ): Result[Option[Int]] =
    section.extra(key) match
      case None => Right(None)
      case Some(raw) =>
        raw.trim.toIntOption
          .filter(_ >= min)
          .map(Some(_))
          .toRight(
            ConfigurationError(
              s"Configured provider '$providerName' has an invalid $key: " +
                s"llm4s.providers.$providerName.$key must be $expected, got '$raw'",
              List(key)
            )
          )

  // Extras arrive as strings. HOCON's own boolean spellings are accepted (true/yes/on,
  // false/no/off), so `streamUsage = off` means what it would for any HOCON boolean. A section
  // built in code, not through validation, may lack the key; it then takes the declared default.
  private def parseStreamUsage(providerName: String, value: Option[String]): Result[Boolean] =
    value.map(_.trim.toLowerCase(Locale.ROOT)) match
      case None                         => Right(true)
      case Some("true" | "yes" | "on")  => Right(true)
      case Some("false" | "no" | "off") => Right(false)
      case Some(_) =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName' has an invalid $StreamUsageKey: " +
              s"llm4s.providers.$providerName.$StreamUsageKey must be true or false, got '${value.getOrElse("")}'"
          )
        )

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAICompatibleConfig](id, config)
      .flatMap(OpenAICompatibleClient(_, options.metrics, options.exchangeLogging))
