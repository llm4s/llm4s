package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.EmbeddingClientOptions

/**
 * What [[EmbeddingProviderDescriptor.buildConfig]] is given besides the section itself.
 *
 * The embedding counterpart of [[ProviderConfigContext]]: one value, so that something a later provider needs
 * is a new field here and not a new signature for every descriptor.
 *
 * @param modelOverride the `<model>` half of `EMBEDDING_MODEL=<id>/<model>`, when the unified form was used. It
 *                      takes precedence over the section's `model`.
 */
@Stable
final case class EmbeddingConfigContext private (modelOverride: Option[String]):

  /** Sets the model that takes precedence over the section's. */
  def withModelOverride(modelOverride: String): EmbeddingConfigContext = copy(modelOverride = Some(modelOverride))

  /** Sets, or clears, the model that takes precedence over the section's. */
  def withModelOverride(modelOverride: Option[String]): EmbeddingConfigContext = copy(modelOverride = modelOverride)

object EmbeddingConfigContext:

  /** The context for a selection; with no argument, the section alone decides the model. */
  def apply(modelOverride: Option[String] = None): EmbeddingConfigContext =
    new EmbeddingConfigContext(modelOverride)

/**
 * What [[EmbeddingProviderDescriptor.build]] is given besides the config itself.
 *
 * The embedding counterpart of [[ProviderBuildContext]].
 *
 * @param options the caller's options: their own HTTP client
 */
@Stable
final case class EmbeddingBuildContext private (options: EmbeddingClientOptions):

  /**
   * The HTTP client to send requests through, when the caller supplied one: `options.httpClient`. The caller
   * owns it, so the provider does not close it; `None` means the provider builds its own from the config's
   * `network` settings.
   */
  def httpClient: Option[Llm4sHttpClient] = options.httpClient

  /** Sets the caller's options. */
  def withOptions(options: EmbeddingClientOptions): EmbeddingBuildContext = copy(options = options)

object EmbeddingBuildContext:

  /** The context for `options`; with no argument, a provider that builds its own HTTP client. */
  def apply(options: EmbeddingClientOptions = EmbeddingClientOptions.default): EmbeddingBuildContext =
    new EmbeddingBuildContext(options)
