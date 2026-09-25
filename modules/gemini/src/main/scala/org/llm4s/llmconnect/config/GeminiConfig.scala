package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Google Gemini API.
 *
 * Prefer [[GeminiConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * @param apiKey        Google API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"gemini-2.0-flash"`.
 * @param baseUrl       API base URL; defaults to [[GeminiConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class GeminiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                 = ProviderId("gemini")
  override def endpointUrl: Option[String]            = Some(baseUrl)
  override def withModel(model: String): GeminiConfig = copy(model = model)
  override def toString: String =
    s"GeminiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

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
