package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-ollama`'s providers: the Ollama chat client and the Ollama
 * embedding provider, both under the id `ollama`.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-ollama` dependency is all it takes for `ProviderRegistry.default`
 * to resolve `provider = "ollama"` and `EMBEDDING_MODEL=ollama/<model>`. Where
 * discovery cannot run - a fat jar whose services files were overwritten rather
 * than merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.builtin.withModule(new Llm4sOllamaModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sOllamaModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(OllamaProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(OllamaEmbeddingProvider)
