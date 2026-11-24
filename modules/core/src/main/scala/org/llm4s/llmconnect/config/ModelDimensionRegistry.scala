package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result

/**
 * Lookup service for embedding model vector dimensions.
 *
 * Answers from the embedding providers in the caller's [[org.llm4s.llmconnect.spi.ProviderRegistry]]:
 * each [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]] declares the
 * dimensions of the models it knows, so a provider module brings its own and
 * nothing here needs editing when one is added.
 */
@Stable
object ModelDimensionRegistry {

  /**
   * The vector dimensions `model` produces under `provider`.
   *
   * @param provider an embedding provider id or alias, as in `EMBEDDING_MODEL=<provider>/<model>`.
   * @return `Left` with a [[org.llm4s.error.ConfigurationError]] when the provider is not
   *         registered - the registry's error, naming what is - or is registered but does
   *         not declare `model`.
   */
  def getDimension(provider: String, model: ModelName)(using registry: ProviderRegistry): Result[Int] =
    registry
      .resolveEmbedding(registry.canonicalEmbeddingId(provider))
      .flatMap(_.dimensionsOf(model).toRight(unknownModel(provider, model)))

  private def unknownModel(provider: String, model: ModelName): ConfigurationError =
    ConfigurationError(s"[ModelDimensionRegistry] Unknown model '$model' for provider '$provider'")
}
