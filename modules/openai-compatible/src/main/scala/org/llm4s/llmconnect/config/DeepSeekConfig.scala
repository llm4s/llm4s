package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory

/**
 * Configuration for the DeepSeek API.
 *
 * Prefer [[DeepSeekConfig.fromValues]] over the primary constructor; it
 * resolves `contextWindow` and `reserveCompletion` automatically, and logs a
 * warning for unknown or legacy model names.
 *
 * @param apiKey        DeepSeek API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"deepseek-chat"` or `"deepseek-reasoner"`.
 * @param baseUrl       API base URL; defaults to [[DeepSeekConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class DeepSeekConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                   = ProviderId("deepseek")
  override def endpointUrl: Option[String]              = Some(baseUrl)
  override def withModel(model: String): DeepSeekConfig = copy(model = model)
  override def toString: String =
    s"DeepSeekConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

object DeepSeekConfig {
  private val logger          = LoggerFactory.getLogger(getClass)
  private val standardReserve = 8192

  val DEFAULT_BASE_URL: String = "https://api.deepseek.com"

  private def deepSeekFallback(modelName: String): (Int, Int) =
    // Explicit allowlist based on official DeepSeek API (as of Feb 2026)
    // Source: https://api-docs.deepseek.com/quick_start/pricing
    modelName.toLowerCase match {
      case "deepseek-chat" | "deepseek/deepseek-chat" | "deepseek-reasoner" | "deepseek/deepseek-reasoner" =>
        (128000, standardReserve)
      case "deepseek-chat-r1" | "deepseek/deepseek-chat-r1" | "deepseek-r1-distill" | "deepseek/deepseek-r1-distill" |
          "deepseek-coder" | "deepseek/deepseek-coder" | "deepseek-v3" | "deepseek/deepseek-v3" =>
        logger.warn(s"Legacy/variant model $modelName - may not be available via official API")
        (128000, standardReserve)
      case _ =>
        logger.warn(s"Unknown DeepSeek model: $modelName, using conservative 128K fallback")
        (128000, standardReserve)
    }

  /**
   * Constructs a [[DeepSeekConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * Unknown or legacy model names produce a warning log but still succeed,
   * falling back to a conservative 128K context window.
   *
   * @param modelName Model identifier; see DeepSeek API docs for the current
   *                  allowlist (`"deepseek-chat"`, `"deepseek-reasoner"`).
   * @param apiKey    DeepSeek API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to
   *                  [[DeepSeekConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[DeepSeekConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("DeepSeek", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("DeepSeek", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("deepseek"),
        modelName = modelName,
        defaultContextWindow = 64000,
        defaultReserve = standardReserve,
        fallbackResolver = deepSeekFallback
      )
      DeepSeekConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
