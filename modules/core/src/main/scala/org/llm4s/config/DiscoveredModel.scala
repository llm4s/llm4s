package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }

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
