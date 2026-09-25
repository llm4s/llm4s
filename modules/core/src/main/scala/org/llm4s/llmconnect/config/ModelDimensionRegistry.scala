package org.llm4s.llmconnect.config

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
 *
 * The one provider answered locally is `local`, the non-text encoders used by
 * `ModelSelector`. It is not an embedding provider - nothing can be configured
 * with it - so it has no descriptor to declare them.
 */
object ModelDimensionRegistry {

  /** The provider name under which the local non-text encoders are looked up. */
  val LocalProvider: String = "local"

  private val localDimensions: Map[String, Int] = Map(
    "openclip-vit-b32" -> 512,
    "wav2vec2-base"    -> 768,
    "timesformer-base" -> 768
  )

  /**
   * The vector dimensions `model` produces under `provider`.
   *
   * @param provider an embedding provider id or alias, as in `EMBEDDING_MODEL=<provider>/<model>`.
   * @return `Left` with a [[org.llm4s.error.ConfigurationError]] when the provider is not
   *         registered - the registry's error, naming what is - or is registered but does
   *         not declare `model`.
   */
  def getDimension(provider: String, model: String)(using registry: ProviderRegistry): Result[Int] =
    if (provider.trim.equalsIgnoreCase(LocalProvider)) localDimension(model)
    else
      registry
        .resolveEmbedding(registry.canonicalEmbeddingId(provider))
        .flatMap(_.dimensionsOf(model).toRight(unknownModel(provider, model)))

  /**
   * The vector dimensions of one of the local non-text encoders.
   *
   * Separate from [[getDimension]] so that selecting a local model never triggers
   * provider discovery, which it has no use for.
   */
  def localDimension(model: String): Result[Int] =
    localDimensions.get(model).toRight(unknownModel(LocalProvider, model))

  private def unknownModel(provider: String, model: String): ConfigurationError =
    ConfigurationError(s"[ModelDimensionRegistry] Unknown model '$model' for provider '$provider'")
}
