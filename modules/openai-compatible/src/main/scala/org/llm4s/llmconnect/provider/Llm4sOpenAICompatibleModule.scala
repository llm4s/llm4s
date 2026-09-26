package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-openai-compatible`'s providers: the generic
 * `openai-compatible` provider, for any endpoint speaking the OpenAI
 * `/chat/completions` API, DeepSeek (`deepseek`) and Z.ai (`zai`), each running on
 * [[OpenAICompatibleClient]] with its own [[OpenAICompatibleDialect]].
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-openai-compatible` dependency is all it takes for
 * `ProviderRegistry.default` to resolve these ids. Where discovery cannot run -
 * a fat jar whose services files were overwritten rather than merged - register
 * it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.builtin.withModule(new Llm4sOpenAICompatibleModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sOpenAICompatibleModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(OpenAICompatibleProvider, DeepSeekProvider, ZaiProvider)
