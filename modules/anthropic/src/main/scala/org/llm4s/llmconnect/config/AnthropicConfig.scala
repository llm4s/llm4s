package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Anthropic Claude API.
 *
 * Prefer [[AnthropicConfig.fromValues]], which validates the values and
 * resolves `contextWindow` and `reserveCompletion` from the bundled model
 * catalogue. The constructor is private: build one with the companion `apply`
 * and adjust it with the `with*` setters. Java and Kotlin, which cannot see
 * Scala default arguments, use `AnthropicConfig.apply(apiKey, model)` and the
 * setters, so adding a field never breaks them.
 *
 * @param apiKey        Anthropic API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
 * @param baseUrl       API base URL; defaults to [[AnthropicConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Stable
final case class AnthropicConfig private (
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts
) extends ProviderConfig:
  override val providerId: ProviderId                                    = ProviderId("anthropic")
  override def endpointUrl: Option[String]                               = Some(baseUrl)
  override def withModel(model: String): AnthropicConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): AnthropicConfig = copy(timeouts = timeouts)

  def withApiKey(apiKey: String): AnthropicConfig                    = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): AnthropicConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): AnthropicConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): AnthropicConfig = copy(reserveCompletion = reserveCompletion)
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

  /** Builds a config without validating it; [[fromValues]] validates. */
  def apply(
    apiKey: String,
    model: String,
    baseUrl: String,
    contextWindow: Int,
    reserveCompletion: Int
  ): AnthropicConfig =
    new AnthropicConfig(apiKey, model, baseUrl, contextWindow, reserveCompletion, ProviderTimeouts.default)

  /**
   * The API key and model, every other field at its default: the entry point for Java and Kotlin,
   * which do not see Scala default arguments. The base URL is [[DEFAULT_BASE_URL]], and
   * `contextWindow` and `reserveCompletion` come from the model name alone (200k for current
   * Claude models); set them, and the rest, with the `with*` setters, or use [[fromValues]] to
   * consult the bundled model catalogue.
   */
  def apply(apiKey: String, model: String): AnthropicConfig = {
    val (cw, rc) = anthropicFallback(model)
    apply(apiKey, model, DEFAULT_BASE_URL, cw, rc)
  }

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
