package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Google Gemini API.
 *
 * Prefer [[GeminiConfig.fromValues]], which validates the values, normalises `baseUrl` and
 * resolves `contextWindow` and `reserveCompletion` from the bundled model catalogue.
 * The constructor is private: build one with the companion `apply` and adjust
 * it with the `with*` setters. Java and Kotlin, which cannot see Scala default
 * arguments, use `GeminiConfig.apply(apiKey, model)` and the setters, so adding
 * a field never breaks them.
 *
 * @param apiKey        Google API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"gemini-2.0-flash"`.
 * @param baseUrl       API base URL; defaults to [[GeminiConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 * @param googleSearch whether to ground answers in Google Search: the section's `googleSearch` key. When set,
 *                 every request carries Gemini's `google_search` tool, the model may search before answering,
 *                 and the sources it used arrive on `Completion.citations`. Off by default; billed by Google.
 */
@Stable
final case class GeminiConfig private (
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  googleSearch: Boolean,
  override val network: HttpNetworkSettings
) extends ProviderConfig:
  override val providerId: ProviderId                                  = ProviderId("gemini")
  override def endpointUrl: Option[String]                             = Some(baseUrl)
  override def withModel(model: String): GeminiConfig                  = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): GeminiConfig  = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): GeminiConfig = copy(network = network)

  def withApiKey(apiKey: String): GeminiConfig                    = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): GeminiConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): GeminiConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): GeminiConfig = copy(reserveCompletion = reserveCompletion)
  def withGoogleSearch(enabled: Boolean): GeminiConfig            = copy(googleSearch = enabled)
  override def toString: String =
    s"GeminiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion, googleSearch=$googleSearch)"

object GeminiConfig {

  /**
   * The Gemini API base URL used when a named provider section sets no `baseUrl`.
   *
   * This was `DefaultConfig.DEFAULT_GEMINI_BASE_URL` in `llm4s-core` until the
   * provider moved to `llm4s-gemini` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta"

  private val standardReserve = 8192
  private val DefaultApiPath  = "/v1beta"

  /**
   * Builds a config without validating it or normalising `baseUrl`; [[fromValues]] does both.
   * `timeouts` defaults to [[ProviderTimeouts.default]] and `googleSearch` to `false`.
   */
  def apply(
    apiKey: String,
    model: String,
    baseUrl: String,
    contextWindow: Int,
    reserveCompletion: Int,
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    googleSearch: Boolean = false
  ): GeminiConfig =
    new GeminiConfig(
      apiKey,
      model,
      baseUrl,
      contextWindow,
      reserveCompletion,
      timeouts,
      googleSearch,
      network = HttpNetworkSettings.default
    )

  /**
   * The API key and model, every other field at its default: the entry point for Java and Kotlin,
   * which do not see Scala default arguments. The base URL is [[DEFAULT_BASE_URL]], Google Search
   * grounding is off, and `contextWindow` and `reserveCompletion` come from the model name alone
   * (1M tokens for current Gemini models); set them, and the rest, with the `with*` setters, or use
   * [[fromValues]] to consult the bundled model catalogue.
   */
  def apply(apiKey: String, model: String): GeminiConfig = {
    val (cw, rc) = geminiFallback(model)
    apply(apiKey, model, DEFAULT_BASE_URL, cw, rc)
  }

  private def geminiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gemini-2")     => (1048576, standardReserve)
      case name if name.contains("gemini-1.5")   => (1048576, standardReserve)
      case name if name.contains("gemini-1.0")   => (32768, standardReserve)
      case name if name.contains("gemini-pro")   => (1048576, standardReserve)
      case name if name.contains("gemini-flash") => (1048576, standardReserve)
      case _                                     => (1048576, standardReserve)
    }

  /**
   * Constructs a [[GeminiConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"gemini-2.0-flash"`.
   * @param apiKey    Google API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[GeminiConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Gemini", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Gemini", "baseUrl", baseUrl)
    } yield {
      val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("gemini", "google"),
        modelName = modelName,
        defaultContextWindow = 1048576,
        defaultReserve = standardReserve,
        fallbackResolver = geminiFallback
      )
      GeminiConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = normalizedBaseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }

  private def normalizeBaseUrl(baseUrl: String): String = {
    val trimmed = baseUrl.trim.stripSuffix("/")
    if trimmed.matches(""".*/v\d+(?:alpha|beta)?$""") then trimmed
    else s"$trimmed$DefaultApiPath"
  }
}
