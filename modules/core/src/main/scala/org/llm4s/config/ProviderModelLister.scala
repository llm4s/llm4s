package org.llm4s.config

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderId }

/**
 * A model discovered from a provider's live model-listing endpoint.
 *
 *  @param name     the model identifier as reported by the provider
 *  @param provider the `ProviderId` that owns this model
 *  @param metadata optional key/value pairs of additional model metadata (e.g. display name, token limits)
 */
final case class DiscoveredModel(
  name: ModelName,
  provider: ProviderId,
  metadata: Map[String, String] = Map.empty
)

/**
 * Discovers available models from a provider's live API endpoint.
 *
 * `llm4s-core` ships no provider and so no lister: each provider module supplies one on its
 * descriptor. Most call `ProviderModelListers.openAICompatible` in `llm4s-openai-compatible`,
 * the factory for the OpenAI `/models` shape; the rest implement this trait.
 */
trait ProviderModelLister:
  /**
   * Fetches the list of available models for the given provider configuration.
   *
   *  @param config     the validated named provider configuration containing credentials and base URL
   *  @param httpClient the HTTP client to use for API requests
   *  @return `Right` with the list of discovered models, or `Left` with an error
   */
  def listModels(
    config: NamedProviderConfig,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]]
