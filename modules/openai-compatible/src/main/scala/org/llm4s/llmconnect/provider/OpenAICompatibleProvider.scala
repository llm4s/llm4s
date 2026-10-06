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
import org.slf4j.LoggerFactory

import java.net.URI
import java.util.Locale
import scala.util.Try

/**
 * Registration for the generic `openai-compatible` provider: any endpoint
 * speaking the OpenAI `/chat/completions` API, with no module and no code.
 *
 * A named provider section with `provider = "openai-compatible"` needs a
 * `baseUrl` and a `model`; `apiKey` and `headers` are optional (local servers need
 * no key). It also accepts four provider-specific keys: `contextWindow` and
 * `reserveCompletion` (whole numbers; the model's window cannot be known from an
 * arbitrary endpoint, so these default to [[org.llm4s.llmconnect.config.OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW]]
 * and a quarter of the window up to [[org.llm4s.llmconnect.config.OpenAICompatibleConfig.DEFAULT_RESERVE_COMPLETION]]),
 * `registryProvider`, and `streamUsage` (default `true`), which controls whether a streaming
 * request asks for token usage with `stream_options.include_usage`; set it to
 * `false` for a server that rejects that field.
 *
 * '''Where the context window comes from.''' In this order: the section's `contextWindow`; else the
 * model registry's entry for `model` under the `registryProvider` the section names (`groq`,
 * `together_ai`, `fireworks_ai`, `xai`, `perplexity`, ...); else, when the section names none, the entry
 * under the provider inferred from the `baseUrl` host; else the default. An explicit `registryProvider` is
 * an instruction, so it switches the inference off. The host is inferred only by an exact match on the
 * URL's host (`api.groq.com`, `api.together.xyz`, `api.together.ai`, `api.fireworks.ai`, `api.x.ai`,
 * `api.perplexity.ai`), never by a substring, so a look-alike host such as `api.groq.com.evil.example` gets
 * no registry window. Only the window is taken from the registry: `reserveCompletion` keeps its own rule
 * (a quarter of the window, up to 2048) because a registry entry's output limit can be as large as the whole
 * window. The lookup is strict - only the named provider's own entry for exactly that model id counts, never a substring match or another provider's entry for the same name - and a model the registry does not know, or lists with no input limit, gets the default.
 * A registry window below the default is ignored too: many entries (most of Fireworks' older and newer
 * models, Perplexity's retired ones) carry a 4096 placeholder for input, output and total alike, which would
 * shrink an 80k-context model's prompt budget below the default and reject a `reserveCompletion` that fits it.
 * The registry can therefore only enlarge the window, never shrink it; a model whose window really is smaller
 * sets `contextWindow`.
 * Several sections can use it side by side:
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

  /**
   * The key naming the model registry provider (`groq`, `together_ai`, `fireworks_ai`, `xai`, `perplexity`,
   * ...) whose entry for `model` supplies the context window when `contextWindow` is not set. Without it the
   * provider is inferred from the `baseUrl` host for the hosts in [[RegistryHosts]]; with it, no inference
   * happens.
   */
  val RegistryProviderKey: String = "registryProvider"

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * The registry provider for each hosted API whose models the registry knows, by the exact host of the
   * `baseUrl`. NVIDIA NIM is left out on purpose: the registry holds three `nvidia_nim` entries, none a chat
   * model with an input limit, so inferring it would find nothing; `registryProvider = "nvidia_nim"` still works.
   */
  private val RegistryHosts: Map[String, String] = Map(
    "api.groq.com"      -> "groq",
    "api.together.xyz"  -> "together_ai",
    "api.together.ai"   -> "together_ai",
    "api.fireworks.ai"  -> "fireworks_ai",
    "api.x.ai"          -> "xai",
    "api.perplexity.ai" -> "perplexity"
  )

  /**
   * The registry provider for a `baseUrl`, from the URL's host alone and only on an exact match with one of
   * [[RegistryHosts]]: `https://api.groq.com.evil.example/v1`, `https://api.groq.com@evil.example/v1` (whose host
   * is `evil.example`) and `https://evil.example/api.groq.com` all give `None`.
   */
  private[llm4s] def inferRegistryProvider(baseUrl: String): Option[String] =
    Try(URI.create(baseUrl.trim)).toOption
      .flatMap(uri => Option(uri.getHost))
      .map(_.toLowerCase(Locale.ROOT))
      .flatMap(RegistryHosts.get)

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
          s"the model's context window in tokens, a positive whole number; overrides the model registry " +
            s"(default: the registry's when it is at least ${OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW}, " +
            s"else ${OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW})"
        ),
        ProviderConfigKey.optional(
          ReserveCompletionKey,
          "the tokens held back from the prompt for the reply, a whole number less than contextWindow"
        ),
        ProviderConfigKey.optional(
          RegistryProviderKey,
          "the model registry provider (e.g. groq, together_ai, fireworks_ai, xai, perplexity) whose entry for " +
            "model gives the context window when contextWindow is not set; inferred from the baseUrl host for " +
            "Groq, Together, Fireworks, xAI and Perplexity"
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
        contextWindow = contextWindow.orElse(
          registryWindow(providerName, section.model.asString, baseUrl, section.extra(RegistryProviderKey))
        ),
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

  // The context window the model registry gives `model`, under the explicit `registryProvider` or, when the
  // section names none, the provider inferred from the `baseUrl` host. `None` when there is no provider to ask,
  // the registry has no entry, its entry has no input limit, or the limit is below the default (a placeholder
  // in many entries, and the registry may only enlarge the window); the caller then uses the default. A miss
  // under an explicit provider is a warning, since the section asked for it; under an inferred one it is not.
  private def registryWindow(
    providerName: String,
    model: String,
    baseUrl: String,
    explicit: Option[String]
  )(using resolver: ContextWindowResolver): Option[Int] =
    explicit.orElse(inferRegistryProvider(baseUrl)).flatMap { registryProvider =>
      resolver.strictContextWindow(registryProvider, model) match
        case Some(window) if window < OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW =>
          val message =
            s"Configured provider '$providerName': ignoring the model registry's contextWindow $window for " +
              s"$registryProvider/$model, below the default ${OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW} " +
              s"(often a placeholder); using the default. Set contextWindow to the model's real limit"
          if explicit.isDefined then logger.warn(message) else logger.info(message)
          None
        case Some(window) =>
          logger.info(
            s"Configured provider '$providerName': contextWindow $window taken from the model registry " +
              s"entry $registryProvider/$model; set contextWindow to override"
          )
          Some(window)
        case None =>
          val message =
            s"Configured provider '$providerName': the model registry has no context window for " +
              s"$registryProvider/$model; using the default ${OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW}. " +
              s"Set contextWindow to the model's real limit"
          if explicit.isDefined then logger.warn(message) else logger.info(message)
          None
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
