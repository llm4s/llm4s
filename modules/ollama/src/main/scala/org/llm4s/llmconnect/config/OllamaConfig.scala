package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId
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
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  override val timeouts: ProviderTimeouts
) extends ProviderConfig:
  override val providerId: ProviderId                                 = ProviderId("ollama")
  override def endpointUrl: Option[String]                            = Some(baseUrl)
  override def withModel(model: String): OllamaConfig                 = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): OllamaConfig = copy(timeouts = timeouts)

  def withBaseUrl(baseUrl: String): OllamaConfig                  = copy(baseUrl = baseUrl)
  def withContextWindow(contextWindow: Int): OllamaConfig         = copy(contextWindow = contextWindow)
  def withReserveCompletion(reserveCompletion: Int): OllamaConfig = copy(reserveCompletion = reserveCompletion)

object OllamaConfig {
  private val standardReserve = 4096

  /** Builds a config without validating it; [[fromValues]] validates. */
  def apply(model: String, baseUrl: String, contextWindow: Int, reserveCompletion: Int): OllamaConfig =
    new OllamaConfig(model, baseUrl, contextWindow, reserveCompletion, ProviderTimeouts.default)

  /**
   * The model and server URL, every other field at its default: the entry point for Java and
   * Kotlin, which do not see Scala default arguments. `contextWindow` and `reserveCompletion` come
   * from the model name alone (`llama3` is 8192); set them with the `with*` setters, or use
   * [[fromValues]] to consult the bundled model catalogue.
   */
  def apply(model: String, baseUrl: String): OllamaConfig = {
    val (cw, rc) = ollamaFallback(model)
    apply(model, baseUrl, cw, rc)
  }

  private def ollamaFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("llama2")    => (4096, standardReserve)
      case name if name.contains("llama3")    => (8192, standardReserve)
      case name if name.contains("codellama") => (16384, standardReserve)
      case name if name.contains("mistral")   => (32768, standardReserve)
      case _                                  => (8192, standardReserve)
    }

  /**
   * Constructs an [[OllamaConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier as registered in Ollama.
   * @param baseUrl   Ollama server URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[OllamaConfig] =
    ProviderConfig.nonEmpty("Ollama", "baseUrl", baseUrl).map { _ =>
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("ollama"),
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
