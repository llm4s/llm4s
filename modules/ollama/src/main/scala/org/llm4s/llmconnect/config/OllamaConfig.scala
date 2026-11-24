package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result

/**
 * Configuration for a locally-running Ollama instance.
 *
 * Ollama requires no API key — authentication is handled at the network
 * level by controlling access to the Ollama endpoint. Prefer
 * [[OllamaConfig.fromValues]], which validates the values and resolves
 * `contextWindow` and `reserveCompletion` from the bundled model catalogue.
 * The constructor is private: build one with the companion `apply` and adjust
 * it with the `with*` setters. Java and Kotlin, which cannot see Scala default
 * arguments, use `OllamaConfig.apply(model, baseUrl)` and the setters, so
 * adding a field never breaks them.
 *
 * @param model         Model identifier as registered in Ollama, e.g. `"llama3"`.
 * @param baseUrl       Ollama server URL, e.g. `"http://localhost:11434"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 * @param timeouts how long a request and a stream may take: the section's `timeouts` block. An absent
 *                 value keeps the client's own default ([[ProviderTimeouts]])
 */
@Stable
final case class OllamaConfig private (
  model: ModelName,
  baseUrl: BaseUrl,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts,
  override val network: HttpNetworkSettings
) extends ProviderConfig:
  override val providerId: ProviderId                                  = ProviderId("ollama")
  override def endpointUrl: Option[BaseUrl]                            = Some(baseUrl)
  override def withModel(model: ModelName): OllamaConfig               = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): OllamaConfig  = copy(timeouts = timeouts)
  override def withNetwork(network: HttpNetworkSettings): OllamaConfig = copy(network = network)

  /** Always `None`: this provider authenticates without an API key. */
  override def apiKeyProvider: Option[org.llm4s.llmconnect.auth.ApiKeyProvider] = None

  /** Always a `ConfigurationError`: this provider takes no API key, so there is none to supply per request. */
  override def withApiKeyProvider(provider: org.llm4s.llmconnect.auth.ApiKeyProvider): Result[Nothing] =
    ProviderConfig.noApiKeyProvider(providerId)

  def withBaseUrl(baseUrl: BaseUrl): OllamaConfig                 = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): OllamaConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): OllamaConfig = copy(reserveCompletion = reserveCompletion)

object OllamaConfig {
  private val standardReserve = 4096

  /** Context window used when `apply` is given none: 8192 tokens. */
  val DEFAULT_CONTEXT_WINDOW: Int = 8192

  /** Completion reserve used when `apply` is given none: 4096 tokens. */
  val DEFAULT_RESERVE_COMPLETION: Int = standardReserve

  /**
   * Builds a config without validating it; [[fromValues]] validates. Every field after `baseUrl` has a
   * default: `contextWindow` and `reserveCompletion` the fixed [[DEFAULT_CONTEXT_WINDOW]] and
   * [[DEFAULT_RESERVE_COMPLETION]], whatever the model; the two-argument `apply` and [[fromValues]]
   * derive them from the model name instead.
   */
  def apply(
    model: ModelName,
    baseUrl: BaseUrl,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    reserveCompletion: Int = DEFAULT_RESERVE_COMPLETION,
    timeouts: ProviderTimeouts = ProviderTimeouts.default
  ): OllamaConfig =
    new OllamaConfig(
      model,
      baseUrl,
      contextWindow,
      reserveCompletion,
      timeouts,
      network = HttpNetworkSettings.default
    )

  /**
   * The model and server URL, every other field at its default: the entry point for Java and
   * Kotlin, which do not see Scala default arguments. `contextWindow` and `reserveCompletion` come
   * from the model name alone (`llama3` is 8192); set them with the `with*` setters, or use
   * [[fromValues]] to consult the bundled model catalogue.
   */
  def apply(model: ModelName, baseUrl: BaseUrl): OllamaConfig = {
    val ContextLimits(cw, rc) = ollamaFallback(model)
    apply(model, baseUrl, contextWindow = cw, reserveCompletion = rc)
  }

  private def ollamaFallback(modelName: ModelName): ContextLimits =
    modelName.asString match {
      case name if name.contains("llama2")    => ContextLimits(4096, standardReserve)
      case name if name.contains("llama3")    => ContextLimits(8192, standardReserve)
      case name if name.contains("codellama") => ContextLimits(16384, standardReserve)
      case name if name.contains("mistral")   => ContextLimits(32768, standardReserve)
      case _                                  => ContextLimits(8192, standardReserve)
    }

  /**
   * Constructs an [[OllamaConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier as registered in Ollama.
   * @param baseUrl   Ollama server URL; must be non-empty.
   */
  def fromValues(
    modelName: ModelName,
    baseUrl: BaseUrl
  )(using resolver: ContextWindowResolver): Result[OllamaConfig] =
    ProviderConfig.nonEmpty("Ollama", "baseUrl", baseUrl.asUrl).map { _ =>
      val ContextLimits(cw, rc) = resolver.resolve(
        lookupProviders = Seq(ProviderId("ollama")),
        modelName = modelName,
        defaultContextWindow = 8192,
        defaultReserve = standardReserve,
        fallbackResolver = ollamaFallback
      )
      OllamaConfig(
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
}
