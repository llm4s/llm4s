package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the xAI (Grok) API.
 *
 * Prefer [[XaiConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` from the model registry (provider `xai`), falling
 * back to xAI's published windows for models the registry does not list.
 *
 * @param apiKey        xAI API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"grok-4.7"`.
 * @param baseUrl       API base URL; defaults to [[XaiConfig.DEFAULT_BASE_URL]]. Requests go to
 *                      `<baseUrl>/chat/completions`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Experimental
case class XaiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts = ProviderTimeouts.default
) extends ProviderConfig:
  override val providerId: ProviderId                              = ProviderId("xai")
  override def endpointUrl: Option[String]                         = Some(baseUrl)
  override def withModel(model: String): XaiConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): XaiConfig = copy(timeouts = timeouts)
  override def toString: String =
    s"XaiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

@Experimental
object XaiConfig {
  private val standardReserve = 8192

  /** xAI's API base. Requests go to `<baseUrl>/chat/completions`. */
  val DEFAULT_BASE_URL: String = "https://api.x.ai/v1"

  private val HalfMillionWindow = """grok-4\.[5-9](?:-.*)?""".r
  private val MillionWindow     = """grok-4\.(?:3|20)(?:-.*)?""".r

  /**
   * The window for a model the registry does not list, from xAI's models page: 500k for
   * `grok-4.5` to `grok-4.7`, 1M for `grok-4.3` and `grok-4.20`, 256k for the other Grok 4
   * models and 131072 otherwise.
   */
  private[config] def xaiFallback(modelName: String): (Int, Int) =
    modelName.toLowerCase match {
      case HalfMillionWindow()               => (500000, standardReserve)
      case MillionWindow()                   => (1000000, standardReserve)
      case name if name.startsWith("grok-4") => (256000, standardReserve)
      case _                                 => (131072, standardReserve)
    }

  /**
   * Constructs an [[XaiConfig]], resolving `contextWindow` and `reserveCompletion` from the
   * model name automatically.
   *
   * @param modelName Model identifier, e.g. `"grok-4.7"`.
   * @param apiKey    xAI API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to [[XaiConfig.DEFAULT_BASE_URL]]
   *                  when loaded via [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[XaiConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("xAI", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("xAI", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("xai"),
        modelName = modelName,
        defaultContextWindow = 131072,
        defaultReserve = standardReserve,
        fallbackResolver = xaiFallback
      )
      XaiConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
