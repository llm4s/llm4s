package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Configuration for a locally-running Ollama instance.
 *
 * Ollama requires no API key — authentication is handled at the network
 * level by controlling access to the Ollama endpoint. Prefer
 * [[OllamaConfig.fromValues]] over the primary constructor.
 *
 * @param model         Model identifier as registered in Ollama, e.g. `"llama3"`.
 * @param baseUrl       Ollama server URL, e.g. `"http://localhost:11434"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class OllamaConfig(
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                 = ProviderId("ollama")
  override def endpointUrl: Option[String]            = Some(baseUrl)
  override def withModel(model: String): OllamaConfig = copy(model = model)

object OllamaConfig {
  private val standardReserve = 4096

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
