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
 *   override val configSpec = EmbeddingConfigSpec(
 *     requiresApiKey = true,
 *     defaultBaseUrl = Some("https://api.jina.ai/v1"),
 *     apiKeyEnv      = Some("JINA_API_KEY")
 *   )
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

  /** What this provider needs from `llm4s.embeddings.<id>`, and its defaults. */
  def configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec()

  /**
   * Turns this provider's config section into the `EmbeddingProviderConfig` its
   * client needs.
   *
   * The default implementation resolves model, base URL and API key against
   * [[configSpec]], which is all any built-in provider needs - including
   * OpenAI, whose key comes from the chat client's `llm4s.openai.apiKey` and
   * which says so with `apiKeyPath` rather than by overriding this. Override it
   * only for a provider whose config genuinely cannot be expressed that way.
   *
   * @param section       the `llm4s.embeddings.<id>` section, already parsed. Empty rather
   *                      than absent when the user configured nothing.
   * @param modelOverride the `<model>` half of `EMBEDDING_MODEL=<id>/<model>`, when the
   *                      unified form was used. Takes precedence over the section.
   */
  def buildConfig(section: EmbeddingProviderSection, modelOverride: Option[String])(using
    lookup: EmbeddingConfigLookup
  ): Result[EmbeddingProviderConfig] =
    for
      model   <- EmbeddingConfigSpec.resolveModel(id, section, modelOverride, configSpec)
      baseUrl <- EmbeddingConfigSpec.resolveBaseUrl(id, section, configSpec)
      apiKey  <- EmbeddingConfigSpec.resolveApiKey(id, section, configSpec, lookup)
    yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)

  /**
   * Constructs the embedding provider for an already-resolved config.
   *
   * The config has been read and validated by the caller, so a `Left` here
   * means something only this provider can detect — a model its API does not
   * offer, say.
   */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider]
