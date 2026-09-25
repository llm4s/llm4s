package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-gemini`'s providers: the Google Gemini API (id `gemini`,
 * alias `google`) and Google Cloud Vertex AI (id `vertexai`, alias `vertex`).
 *
 * Vertex AI ships here rather than in a module of its own because it only
 * calls Google's `publishers/google` (Gemini) models, in the same JSON format
 * as the Gemini API. It differs in endpoint and authentication, not in model
 * family, and adds no dependency.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-gemini` dependency is all it takes for `ProviderRegistry.default`
 * to resolve `provider = "gemini"` and `provider = "vertexai"`. Where discovery
 * cannot run - a fat jar whose services files were overwritten rather than
 * merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.builtin.withModule(new Llm4sGeminiModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sGeminiModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(GeminiProvider, VertexAIProvider)
