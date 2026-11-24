package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * The active text embedding model, as [[Llm4sConfig.textEmbeddingModel]] resolves it: the provider and the model.
 *
 * It was `Llm4sConfig.TextEmbeddingModelSettings(provider: String, modelName: String, dimensions: Int)`, which
 * repeated [[org.llm4s.llmconnect.config.EmbeddingModelConfig]] beside a `String` provider (#2805).
 *
 * @param provider the embedding provider's canonical id, e.g. `ProviderId("openai")`.
 * @param model    the model's name and output dimensions.
 *
 * Build one with [[TextEmbeddingModelSettings.apply]] and change it with the `with*` setters; the constructor and
 * `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class TextEmbeddingModelSettings private (provider: ProviderId, model: EmbeddingModelConfig) {

  /** Sets the embedding provider. */
  def withProvider(provider: ProviderId): TextEmbeddingModelSettings = copy(provider = provider)

  /** Sets the model's name and output dimensions. */
  def withModel(model: EmbeddingModelConfig): TextEmbeddingModelSettings = copy(model = model)
}

object TextEmbeddingModelSettings {

  /** Creates a [[TextEmbeddingModelSettings]]. */
  def apply(provider: ProviderId, model: EmbeddingModelConfig): TextEmbeddingModelSettings =
    new TextEmbeddingModelSettings(provider, model)
}
