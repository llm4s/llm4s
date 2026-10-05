package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule }

/**
 * Registers `llm4s-cohere`'s one provider: the Cohere embedding provider, under the id `cohere`.
 * Cohere's chat client is a dialect in `llm4s-openai-compatible` (under the same id: chat and
 * embedding ids are separate namespaces), so this module overrides only `embeddingProviders`.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding the
 * `llm4s-cohere` dependency is all it takes for `ProviderRegistry.default` to resolve
 * `EMBEDDING_MODEL=cohere/<model>`. Where discovery cannot run - a fat jar whose services
 * files were overwritten rather than merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sCohereModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader` instantiates the class
 * named in the services file through its public no-arg constructor.
 */
final class Llm4sCohereModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(CohereEmbeddingProvider)
