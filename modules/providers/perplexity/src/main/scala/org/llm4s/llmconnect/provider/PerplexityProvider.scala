package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, PerplexityConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.Locale

/**
 * Registration for Perplexity's Agent API: `provider = "perplexity"`.
 *
 * The Agent API (`POST https://api.perplexity.ai/v1/agent`) replaced Sonar Chat Completions, whose support Perplexity
 * ended on 27 September 2026. It speaks the OpenAI Responses format, so it is a client of its own rather than an
 * `openai-compatible` dialect. A section names an Agent API model in its `provider/model` form and takes the key as
 * `apiKey`, or from `llm4s.credentials.perplexity.apiKey`, which `PERPLEXITY_API_KEY` binds:
 *
 * {{{
 * perplexity-main {
 *   provider = "perplexity"
 *   model    = "perplexity/sonar"
 * }
 * }}}
 *
 * Extras: `webSearch` (default `true`) offers the model Perplexity's `web_search` tool on every request;
 * `contextWindow` and `reserveCompletion` override the window, which otherwise comes from the model registry entry
 * of the model's own vendor (`anthropic/claude-sonnet-5-5` looks up `anthropic`'s `claude-sonnet-5-5`), else
 * [[org.llm4s.llmconnect.config.PerplexityConfig.DEFAULT_CONTEXT_WINDOW]].
 */
object PerplexityProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("perplexity")

  /** The key that turns the `web_search` tool on or off. */
  val WebSearchKey: String = "webSearch"

  /** The key overriding the model's context window. */
  val ContextWindowKey: String = "contextWindow"

  /** The key overriding the tokens held back for the reply. */
  val ReserveCompletionKey: String = "reserveCompletion"

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some(PerplexityConfig.DEFAULT_BASE_URL),
    baseUrlExample = "e.g. https://api.perplexity.ai",
    apiKeyEnv = Seq("PERPLEXITY_API_KEY"),
    extras = Seq(
      ProviderConfigKey.optional(
        WebSearchKey,
        "whether every request offers the model Perplexity's web_search tool (billed per search)",
        default = Some("true")
      ),
      ProviderConfigKey.optional(
        ContextWindowKey,
        "the model's context window in tokens, a positive whole number; overrides the model registry " +
          s"(default: the registry's entry for the model's vendor, else ${PerplexityConfig.DEFAULT_CONTEXT_WINDOW})"
      ),
      ProviderConfigKey.optional(
        ReserveCompletionKey,
        "the tokens held back from the prompt for the reply, a whole number less than contextWindow",
        default = Some(PerplexityConfig.DEFAULT_RESERVE_COMPLETION.toString)
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    resolver: ContextWindowResolver
  ): Result[ProviderConfig] =
    val model = section.model.asString
    (for
      apiKey    <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl   <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      webSearch <- parseBoolean(section.extra(WebSearchKey))
      window    <- parseCount(section.extra(ContextWindowKey), ContextWindowKey, min = 1)
      reserve   <- parseCount(section.extra(ReserveCompletionKey), ReserveCompletionKey, min = 0)
      config <- PerplexityConfig.fromValues(
        model = model,
        apiKey = apiKey,
        baseUrl = baseUrl,
        webSearch = webSearch,
        contextWindow = window.orElse(registryWindow(model)).getOrElse(PerplexityConfig.DEFAULT_CONTEXT_WINDOW),
        reserveCompletion = reserve.getOrElse(PerplexityConfig.DEFAULT_RESERVE_COMPLETION),
        headers = section.headers
      )
    yield config).left.map(ProviderConfig.inSection(providerName))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[PerplexityConfig](id, config)
      .flatMap(PerplexityClient(_, options.metrics, options.exchangeLogging))

  /**
   * The model registry's context window for an Agent API model id, `vendor/model`: the strict entry of that vendor
   * for that model, so an unknown name gets none rather than a look-alike's.
   */
  private[provider] def registryWindow(model: String)(using resolver: ContextWindowResolver): Option[Int] =
    model.split("/", 2) match
      case Array(vendor, name) if vendor.nonEmpty && name.nonEmpty =>
        resolver.strictContextWindow(vendor.toLowerCase(Locale.ROOT), name)
      case _ => None

  private def parseBoolean(raw: Option[String]): Result[Boolean] =
    raw.map(_.trim.toLowerCase(Locale.ROOT)) match
      case None | Some("true") => Right(true)
      case Some("false")       => Right(false)
      case Some(other) =>
        Left(ConfigurationError(s"perplexity $WebSearchKey must be true or false, got '$other'", List(WebSearchKey)))

  private def parseCount(raw: Option[String], key: String, min: Int): Result[Option[Int]] =
    raw.map(_.trim) match
      case None => Right(None)
      case Some(text) =>
        text.toIntOption.filter(_ >= min) match
          case Some(n) => Right(Some(n))
          case None =>
            val expected = if min > 0 then "a positive whole number" else "a whole number, 0 or more"
            Left(ConfigurationError(s"perplexity $key must be $expected, got '$text'", List(key)))
