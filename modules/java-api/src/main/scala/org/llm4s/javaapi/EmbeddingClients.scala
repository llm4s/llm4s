package org.llm4s.javaapi

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ModelDimensionRegistry }
import org.llm4s.types.Result

import java.util.OptionalInt

/**
 * Not part of the API: how [[Llm4s]]'s embedding factories turn a loaded embedding config into a [[JEmbeddingClient]].
 * An object of its own, not a member of `Llm4s`, so that taking a Scala `Either` does not put a public static method
 * on the Java facade.
 */
private[javaapi] object EmbeddingClients {

  /**
   * The client for the provider and config `loaded` selected - or why there is none. Its dimensions are the ones the
   * provider module declares for the model, and empty for a model it does not list: the embed call itself needs none,
   * and a reply's vectors say how long they are (#1781).
   */
  def from(loaded: Result[(String, EmbeddingProviderConfig)]): Result[JEmbeddingClient] =
    for {
      selected <- loaded
      (provider, config) = selected
      registry <- Llm4sConfig.modelRegistryService()
      client   <- EmbeddingClient.from(provider, config)(using registry)
    } yield new JEmbeddingClient(client, config.model, declared(provider, config.model))

  /**
   * The dimensions `provider`'s module declares for `model`. The provider is the one the config loaded, so the only
   * refusal left is an unlisted model, which is not a failure here.
   */
  private def declared(provider: String, model: String): OptionalInt =
    ModelDimensionRegistry.getDimension(provider, model).fold(_ => OptionalInt.empty(), OptionalInt.of)
}
