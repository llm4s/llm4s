package org.llm4s.llmconnect.config

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
 */
case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                    = ProviderId("anthropic")
  override def endpointUrl: Option[String]               = Some(baseUrl)
  override def withModel(model: String): AnthropicConfig = copy(model = model)
  override def toString: String =
    s"AnthropicConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

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
   * Constructs an [[AnthropicConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
   * @param apiKey    Anthropic API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[AnthropicConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Anthropic", "apiKey", apiKey)
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
        reserveCompletion = rc
      )
    }
}
