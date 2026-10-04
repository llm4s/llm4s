package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule }

/**
 * Registers `llm4s-jina`'s one provider: the Jina AI embedding provider, under the id `jina`.
 * Jina supplies embeddings and no chat client, so this module overrides only
 * `embeddingProviders`.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding the
 * `llm4s-jina` dependency is all it takes for `ProviderRegistry.default` to resolve
 * `EMBEDDING_MODEL=jina/<model>`. Where discovery cannot run - a fat jar whose services
 * files were overwritten rather than merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sJinaModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader` instantiates the class
 * named in the services file through its public no-arg constructor.
 */
final class Llm4sJinaModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(JinaEmbeddingProvider)
