package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Configuration for Perplexity's Agent API (`POST /v1/agent`).
 *
 * Build one with [[PerplexityConfig.fromValues]]; a `provider = "perplexity"` section builds one for you.
 *
 * @param apiKey            the Perplexity API key, sent as a bearer token; redacted in `toString`.
 * @param model             the Agent API model, in its `provider/model` form, e.g. `"perplexity/sonar"` or
 *                          `"openai/gpt-5.6-sol"`.
 * @param baseUrl           the API host, `https://api.perplexity.ai` by default; requests go to `<baseUrl>/v1/agent`
 *                          (or `<baseUrl>/agent` when the base already ends in `/v1`).
 * @param webSearch         whether each request offers the model Perplexity's `web_search` tool. Each search the
 *                          model runs is billed per invocation.
 * @param contextWindow     the model's context window in tokens.
 * @param reserveCompletion tokens held back from the prompt for the reply; also the `max_output_tokens` sent to an
 *                          `anthropic/` model when the request sets none, since the Agent API requires one for them.
 * @param headers           extra HTTP headers sent with every request: the section's `headers`.
 * @param timeouts          how long a request and a stream may take: the section's `timeouts` block.
 * @param network           proxy and TLS settings.
 * @param apiKeyProvider    asked for the key on every request instead of `apiKey`: a section's `apiKeyFile` sets one.
 */
@Experimental
final case class PerplexityConfig private (
  apiKey: Secret,
  model: String,
  baseUrl: String,
  webSearch: Boolean,
  contextWindow: Int,
  reserveCompletion: Int,
  headers: Map[String, String],
  override val timeouts: ProviderTimeouts,
  override val network: HttpNetworkSettings,
  override val apiKeyProvider: Option[ApiKeyProvider]
) extends ProviderConfig:
  override val providerId: ProviderId                                         = ProviderId("perplexity")
  override def endpointUrl: Option[String]                                    = Some(baseUrl)
  override def withModel(model: String): PerplexityConfig                     = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): PerplexityConfig     = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): PerplexityConfig    = copy(network = network)
  override def withApiKeyProvider(provider: ApiKeyProvider): PerplexityConfig = copy(apiKeyProvider = Some(provider))
  def withApiKeyProvider(provider: Option[ApiKeyProvider]): PerplexityConfig  = copy(apiKeyProvider = provider)
  def withWebSearch(webSearch: Boolean): PerplexityConfig                     = copy(webSearch = webSearch)
  def withHeaders(headers: Map[String, String]): PerplexityConfig             = copy(headers = headers)

  /** The Agent API endpoint requests are posted to. */
  def agentUrl: String = if baseUrl.endsWith("/v1") then s"$baseUrl/agent" else s"$baseUrl/v1/agent"

  override def toString: String =
    s"PerplexityConfig(apiKey=$apiKey, model=$model, baseUrl=$baseUrl, webSearch=$webSearch, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion, headers=${headers.keys.mkString("[", ",", "]")}, " +
      s"timeouts=$timeouts${apiKeyProvider.fold("")(p => s", apiKeyProvider=$p")})"

@Experimental
object PerplexityConfig:

  /** Perplexity's API host. */
  val DEFAULT_BASE_URL: String = "https://api.perplexity.ai"

  /**
   * The context window assumed when neither the section's `contextWindow` nor the model registry gives one.
   * Perplexity publishes no per-model window for the Agent API.
   */
  val DEFAULT_CONTEXT_WINDOW: Int = 128000

  /** The reply reserve, and the `max_output_tokens` an `anthropic/` model is sent when a request sets none. */
  val DEFAULT_RESERVE_COMPLETION: Int = 4096

  private val LocalHosts = Set("localhost", "127.0.0.1", "::1", "[::1]")

  /** `https`, or `http` only to a loopback host: the URL carries the API key. */
  private def requireHttps(url: String): Result[Unit] =
    val parsed = scala.util.Try(java.net.URI.create(url)).toOption
    val scheme = parsed.flatMap(u => Option(u.getScheme)).map(_.toLowerCase(java.util.Locale.ROOT))
    val host   = parsed.flatMap(u => Option(u.getHost)).map(_.toLowerCase(java.util.Locale.ROOT))
    val ok = scheme match
      case Some("https") => host.exists(_.nonEmpty)
      case Some("http")  => host.exists(LocalHosts.contains)
      case _             => false
    Either.cond(
      ok,
      (),
      ConfigurationError(
        "perplexity baseUrl must be an https URL (http is allowed only for localhost)",
        List("baseUrl")
      )
    )

  /**
   * Builds a [[PerplexityConfig]]. The `apiKey` and `model` are trimmed and must not be blank; `baseUrl` loses any
   * trailing `/` and must be `https` (`http` only for `localhost`, `127.0.0.1` and `::1`); `contextWindow` must be
   * positive and `reserveCompletion` 0 or more and less than it. Anything else is a `ConfigurationError`.
   */
  def fromValues(
    model: String,
    apiKey: Secret,
    baseUrl: String = DEFAULT_BASE_URL,
    webSearch: Boolean = true,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    reserveCompletion: Int = DEFAULT_RESERVE_COMPLETION,
    headers: Map[String, String] = Map.empty
  ): Result[PerplexityConfig] =
    val key  = apiKey.reveal.trim
    val name = model.trim
    val base = baseUrl.trim.replaceAll("/+$", "")
    for
      _ <- ProviderConfig.nonEmpty("perplexity", "apiKey", key)
      _ <- ProviderConfig.nonEmpty("perplexity", "model", name)
      _ <- ProviderConfig.nonEmpty("perplexity", "baseUrl", base)
      _ <- requireHttps(base)
      _ <- Either.cond(
        contextWindow > 0,
        (),
        ConfigurationError("perplexity contextWindow must be a positive whole number", List("contextWindow"))
      )
      _ <- Either.cond(
        reserveCompletion >= 0 && reserveCompletion < contextWindow,
        (),
        ConfigurationError(
          s"perplexity reserveCompletion must be 0 or more and less than contextWindow ($contextWindow)",
          List("reserveCompletion")
        )
      )
    yield new PerplexityConfig(
      apiKey = Secret(key),
      model = name,
      baseUrl = base,
      webSearch = webSearch,
      contextWindow = contextWindow,
      reserveCompletion = reserveCompletion,
      headers = headers,
      timeouts = ProviderTimeouts.default,
      network = HttpNetworkSettings.default,
      apiKeyProvider = None
    )
