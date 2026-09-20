package org.llm4s.llmconnect.spi

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Everything `llm4s` needs to know about one embedding provider, supplied by
 * the provider itself.
 *
 * This is [[ProviderDescriptor]]'s counterpart for the embedding half of the
 * SPI, and it is deliberately a separate trait rather than a method on the chat
 * descriptor: the two sets overlap but neither contains the other. OpenAI and
 * Ollama supply both a chat client and an embedding provider, Voyage supplies
 * only embeddings, and Anthropic only chat. Folding embeddings into
 * `ProviderDescriptor` would force an embedding-only provider to implement
 * `buildClient` and `buildConfig` only to fail them.
 *
 * Register it by listing it in an [[Llm4sProviderModule]]'s
 * [[Llm4sProviderModule.embeddingProviders]], exactly as a chat provider is
 * registered, and `EmbeddingClient.from` can reach it with nothing in
 * `llm4s-core` edited.
 *
 * @example
 * {{{
 * object JinaEmbeddings extends EmbeddingProviderDescriptor:
 *   val id = ProviderId("jina")
 *
 *   def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
 *     Right(JinaEmbeddingProvider.fromConfig(config))
 * }}}
 */
trait EmbeddingProviderDescriptor:

  /**
   * Canonical id, e.g. `ProviderId("voyage")`. Must be unique among the
   * embedding providers in a registry.
   *
   * Chat and embedding ids live in separate namespaces, so a provider that
   * supplies both — OpenAI, Ollama — uses the same id for each without a
   * clash. That is what lets `EMBEDDING_MODEL=ollama/nomic-embed-text` and
   * `LLM_MODEL=ollama/llama3` name the same provider.
   */
  def id: ProviderId

  /** Alternative spellings accepted in `EMBEDDING_MODEL=<name>/<model>`, folded onto [[id]]. */
  def aliases: Set[String] = Set.empty

  /**
   * Constructs the embedding provider for an already-resolved config.
   *
   * The config has been read and validated by the caller, so a `Left` here
   * means something only this provider can detect — a model its API does not
   * offer, say.
   */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider]
