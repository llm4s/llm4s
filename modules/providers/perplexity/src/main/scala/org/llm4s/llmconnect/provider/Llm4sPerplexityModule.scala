package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-perplexity`'s provider: the Perplexity Agent API chat client, under the id `perplexity`.
 *
 * Declared in this module's `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding the
 * `llm4s-perplexity` dependency is all it takes for `ProviderRegistry.default` to resolve `provider = "perplexity"`.
 * Where discovery cannot run - a fat jar whose services files were overwritten rather than merged - register it
 * explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sPerplexityModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader` instantiates the class named in the services
 * file through its public no-arg constructor.
 */
final class Llm4sPerplexityModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(PerplexityProvider)
