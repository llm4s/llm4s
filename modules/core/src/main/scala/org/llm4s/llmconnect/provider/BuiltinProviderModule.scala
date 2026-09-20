package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderDescriptor }

/**
 * The `META-INF/services` entry point for the providers `llm4s-core` ships.
 *
 * This is a `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates the class named in the services file through its public no-arg
 * constructor, and a Scala `object` exposes its instance as a `MODULE$` field
 * instead. The same shape is what GraalVM's `ServiceLoaderFeature` needs, so a
 * provider module written this way works under native-image too.
 *
 * It delegates to [[BuiltinProviders]], which stays the readable list.
 */
final class BuiltinProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = BuiltinProviders.chatProviders
