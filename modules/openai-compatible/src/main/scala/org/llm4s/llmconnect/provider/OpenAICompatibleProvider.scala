package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAICompatibleModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAICompatibleConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for the generic `openai-compatible` provider: any endpoint
 * speaking the OpenAI `/chat/completions` API, with no module and no code.
 *
 * A named provider section with `provider = "openai-compatible"` needs a
 * `baseUrl` and a `model`; `apiKey` is optional (local servers need none), and
 * `contextWindow`, `reserveCompletion` and `headers` may be set. Several
 * sections can use it side by side:
 *
 * {{{
 * llm4s.providers {
 *   groq-main {
 *     provider = "openai-compatible"
 *     baseUrl  = "https://api.groq.com/openai/v1"
 *     model    = "llama-3.3-70b-versatile"
 *     apiKey   = ${?GROQ_API_KEY}
 *     contextWindow = 131072
 *   }
 *   local-vllm {
 *     provider = "openai-compatible"
 *     baseUrl  = "http://localhost:8000/v1"
 *     model    = "Qwen/Qwen2.5-7B-Instruct"
 *   }
 * }
 * }}}
 *
 * The client is [[OpenAICompatibleClient]] with the standard dialect: no
 * reasoning parameters and no provider-specific decoding. A provider that needs
 * those gets its own [[OpenAICompatibleDialect]] and descriptor in this module.
 */
object OpenAICompatibleProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId(OpenAICompatibleConfig.ProviderIdName)

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec(requiresBaseUrl = true, baseUrlExample = "e.g. http://localhost:8000/v1")

  override val modelLister: Option[ProviderModelLister] = Some(OpenAICompatibleModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config <- OpenAICompatibleConfig.fromValues(
        model = section.model.asString,
        baseUrl = baseUrl,
        apiKey = section.apiKey.map(_.asKey),
        contextWindow = section.contextWindow,
        reserveCompletion = section.reserveCompletion,
        headers = section.headers
      )
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAICompatibleConfig](id, config)
      .flatMap(OpenAICompatibleClient(_, options.metrics, options.exchangeLogging))
