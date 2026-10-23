package org.llm4s.config

import org.llm4s.annotation.Stable
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
 *
 * Build one with [[DiscoveredModel.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers.
 */
@Stable
final case class DiscoveredModel private (
  name: ModelName,
  provider: ProviderId,
  metadata: Map[String, String]
) {

  /** Sets the model identifier. */
  def withName(name: ModelName): DiscoveredModel = copy(name = name)

  /** Sets the owning provider. */
  def withProvider(provider: ProviderId): DiscoveredModel = copy(provider = provider)

  /** Sets the additional model metadata. */
  def withMetadata(metadata: Map[String, String]): DiscoveredModel = copy(metadata = metadata)
}

object DiscoveredModel {

  /**
   * Creates a [[DiscoveredModel]].
   *
   * @param name     the model identifier as reported by the provider
   * @param provider the provider that owns the model
   * @param metadata additional model metadata
   */
  def apply(name: ModelName, provider: ProviderId, metadata: Map[String, String] = Map.empty): DiscoveredModel =
    new DiscoveredModel(name, provider, metadata)
}

/**
 * Discovers available models from a provider's live API endpoint.
 *
 * `llm4s-core` ships no provider and so no lister: each provider module supplies one on its
 * descriptor. Most call `ProviderModelListers.openAICompatible` in `llm4s-openai-compatible`,
 * the factory for the OpenAI `/models` shape; the rest implement this trait.
 */
@Stable
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
