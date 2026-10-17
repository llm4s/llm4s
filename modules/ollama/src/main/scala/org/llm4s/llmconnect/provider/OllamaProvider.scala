package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OllamaConfigKeys, OllamaModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OllamaConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for a locally-running Ollama instance.
 *
 * A section's `baseUrl` defaults to `http://localhost:11434`, where `ollama serve`
 * listens and where Ollama's own clients look, so a local section needs only
 * `provider` and `model` ([[https://github.com/llm4s/llm4s/issues/1991 #1991]]). For
 * another host, set `baseUrl`, or bind the conventional variable in the section:
 * `baseUrl = ${?OLLAMA_BASE_URL}`. It needs no API key — access is controlled at
 * the network level.
 */
@Stable
object OllamaProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("ollama")

  /** Where `ollama serve` listens by default; used when a section sets no `baseUrl`. */
  private[llm4s] val DefaultBaseUrl: String = "http://localhost:11434"

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    defaultBaseUrl = Some(DefaultBaseUrl),
    baseUrlEnv = Some(OllamaConfigKeys.OLLAMA_BASE_URL)
  )

  override val modelLister: Option[ProviderModelLister] = Some(OllamaModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    ProviderDescriptor
      .resolveBaseUrl(providerName, section, configSpec)
      .flatMap(baseUrl => OllamaConfig.fromValues(section.model.asString, baseUrl))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OllamaConfig](id, config)
      .flatMap(OllamaClient(_, options.metrics, options.exchangeLogging))
