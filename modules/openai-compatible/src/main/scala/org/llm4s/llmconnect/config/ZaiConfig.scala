package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Z.ai GLM API.
 *
 * Prefer [[ZaiConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * @param apiKey        Z.ai API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"GLM-4.7"`.
 * @param baseUrl       API base URL; defaults to [[ZaiConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class ZaiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId              = ProviderId("zai")
  override def endpointUrl: Option[String]         = Some(baseUrl)
  override def withModel(model: String): ZaiConfig = copy(model = model)
  override def toString: String =
    s"ZaiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

object ZaiConfig {
  private val standardReserve = 4096

  val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

  private def zaiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("GLM-4.7") => (200000, standardReserve)
      case name if name.contains("GLM-4.5") => (128000, standardReserve)
      case _                                => (128000, standardReserve)
    }

  /**
   * Constructs a [[ZaiConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"GLM-4.7"`.
   * @param apiKey    Z.ai API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to
   *                  [[ZaiConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[ZaiConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Zai", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Zai", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("zai"),
        modelName = modelName,
        defaultContextWindow = 128000,
        defaultReserve = standardReserve,
        fallbackResolver = zaiFallback
      )
      ZaiConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
