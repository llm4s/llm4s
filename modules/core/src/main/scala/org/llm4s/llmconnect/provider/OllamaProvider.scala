package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ ProviderModelLister, ProviderModelListers }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OllamaConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for a locally-running Ollama instance.
 *
 * Ollama is the one provider with a required `baseUrl` and no default: it runs
 * wherever the user started it. It needs no API key — access is controlled at
 * the network level.
 */
object OllamaProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("ollama")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresBaseUrl = true,
    baseUrlExample = "e.g. http://localhost:11434"
  )

  override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Ollama)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    ProviderDescriptor
      .resolveBaseUrl(providerName, section, configSpec)
      .map(baseUrl => OllamaConfig.fromValues(section.model.asString, baseUrl))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OllamaConfig](id, config)
      .flatMap(OllamaClient(_, options.metrics, options.exchangeLogging))
