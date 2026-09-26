package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-openai`'s providers: the three that share `OpenAIClient` -
 * OpenAI (id `openai`), Azure OpenAI (`azure`) and Requesty (`requesty`) - and
 * the OpenAI embedding provider (`openai`).
 *
 * OpenRouter, DeepSeek and Z.ai speak the same wire format but have their own
 * SDK-free clients, so they are not part of this module
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-openai` dependency is all it takes for `ProviderRegistry.default`
 * to resolve `provider = "openai"`, `"azure"` and `"requesty"`, and
 * `EMBEDDING_MODEL=openai/<model>`. Where discovery cannot run - a fat jar
 * whose services files were overwritten rather than merged - register it
 * explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.builtin.withModule(new Llm4sOpenAIModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sOpenAIModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(OpenAIProvider, AzureProvider, RequestyProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(OpenAIEmbeddingProvider)
