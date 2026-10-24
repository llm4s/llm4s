package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the Z.ai GLM API.
 *
 * Prefer [[ZaiConfig.fromValues]] over the companion `apply`; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * The constructor is private: build one with the companion `apply`, whose defaults cover every
 * field after `model`, and adjust it with the `with*` setters. Java and Kotlin, which cannot see
 * Scala default arguments, use `ZaiConfig.apply(apiKey, model)` and the setters, so adding a field
 * never breaks them ([[https://github.com/llm4s/llm4s/issues/2264 #2264]]).
 *
 * @param apiKey        Z.ai API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"GLM-4.7"`.
 * @param baseUrl       API base URL; defaults to [[ZaiConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Stable
final case class ZaiConfig private (
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  override val network: HttpNetworkSettings
) extends ProviderConfig:
  override val providerId: ProviderId                               = ProviderId("zai")
  override def endpointUrl: Option[String]                          = Some(baseUrl)
  override def withModel(model: String): ZaiConfig                  = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): ZaiConfig  = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): ZaiConfig = copy(network = network)

  def withApiKey(apiKey: String): ZaiConfig                    = copy(apiKey = apiKey)
  def withBaseUrl(baseUrl: String): ZaiConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): ZaiConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): ZaiConfig = copy(reserveCompletion = reserveCompletion)
  override def toString: String =
    s"ZaiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

object ZaiConfig {
  private val standardReserve = 4096

  val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

  /** The context window `apply` uses when none is given; [[fromValues]] resolves the model's own. */
  val DEFAULT_CONTEXT_WINDOW: Int = 128000

  /** The completion reserve `apply` uses when none is given; [[fromValues]] resolves the model's own. */
  val DEFAULT_RESERVE_COMPLETION: Int = 4096

  /**
   * Builds a config without validating it; [[fromValues]] validates and resolves the context window
   * from the model. Every field after `model` has a default.
   */
  def apply(
    apiKey: String,
    model: String,
    baseUrl: String = DEFAULT_BASE_URL,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    reserveCompletion: Int = DEFAULT_RESERVE_COMPLETION,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
  ): ZaiConfig =
    new ZaiConfig(
      apiKey,
      model,
      baseUrl,
      contextWindow,
      reserveCompletion,
      timeouts,
      network = HttpNetworkSettings.default
    )

  /**
   * The API key and model, every other field at its default: the entry point for Java and Kotlin,
   * which do not see Scala default arguments. Set the rest with the `with*` setters.
   */
  def apply(apiKey: String, model: String): ZaiConfig =
    apply(apiKey, model, baseUrl = DEFAULT_BASE_URL)

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
