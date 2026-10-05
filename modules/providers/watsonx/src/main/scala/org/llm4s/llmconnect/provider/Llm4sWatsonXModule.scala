package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * Registers `llm4s-watsonx`'s provider: the IBM watsonx.ai text-generation client, under the id `watsonx`.
 *
 * '''Beta - built on deprecated endpoints.''' IBM's February 2026 release notes
 * (https://www.ibm.com/docs/en/software-hub/5.3.x?topic=new-watsonxai) deprecate the watsonx.ai
 * "Infer text" and "Infer text event stream" endpoints (`/ml/v1/text/generation` and
 * `/generation_stream`) this module uses; IBM points to the chat API. This module has never been run
 * against the live service (no watsonx account), its API is not frozen, and tools are unsupported
 * because of the endpoint. Migration to the chat API: https://github.com/llm4s/llm4s/issues/1314.
 *
 * Declared in this module's
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
 * the `llm4s-watsonx` dependency is all it takes for `ProviderRegistry.default`
 * to resolve `provider = "watsonx"`. Where discovery cannot run - a fat jar whose
 * services files were overwritten rather than merged - register it explicitly:
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sWatsonXModule)
 * }}}
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor.
 */
final class Llm4sWatsonXModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(WatsonXProvider)
