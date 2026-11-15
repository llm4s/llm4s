package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-jlama`'s provider: a model run in-process by Jlama, under the id `jlama`.
 *
 * Declared in this module's `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding the
 * `llm4s-jlama` dependency is all it takes for `ProviderRegistry.default` to resolve `provider = "jlama"`. Where
 * discovery cannot run - a fat jar whose services files were overwritten rather than merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sJlamaModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader` instantiates the class named in the services
 * file through its public no-arg constructor.
 */
@Experimental
final class Llm4sJlamaModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(JlamaProvider)
