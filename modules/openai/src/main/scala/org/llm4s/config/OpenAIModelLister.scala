package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderId }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.provider.{ OpenAIProvider, RequestyProvider }
import org.llm4s.types.Result

/**
 * Model lister for the OpenAI provider, using the OpenAI `/models` endpoint.
 *
 * This was `ProviderModelListers.OpenAI` until the provider moved to
 * `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
object OpenAIModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(ProviderId("openai"), OpenAIProvider.DEFAULT_BASE_URL)

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)

/**
 * Model lister for the Requesty provider, using its OpenAI-compatible `/models` endpoint.
 *
 * This was `ProviderModelListers.Requesty` until the provider moved to
 * `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
object RequestyModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(ProviderId("requesty"), RequestyProvider.DEFAULT_BASE_URL)

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)
