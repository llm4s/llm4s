package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-anthropic`'s provider: the Anthropic Claude API (id `anthropic`).
 *
 * Anthropic supplies a chat client only, so this module overrides
 * `chatProviders` and leaves `embeddingProviders` empty.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-anthropic` dependency is all it takes for `ProviderRegistry.default`
 * to resolve `provider = "anthropic"`. Where discovery cannot run - a fat jar
 * whose services files were overwritten rather than merged - register it
 * explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.builtin.withModule(new Llm4sAnthropicModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sAnthropicModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(AnthropicProvider)
