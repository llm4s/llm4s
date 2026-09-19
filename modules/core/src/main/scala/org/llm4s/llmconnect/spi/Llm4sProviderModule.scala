package org.llm4s.llmconnect.spi

/**
 * A unit of provider registration: everything one module contributes.
 *
 * The discovered unit is a module rather than a single `ProviderDescriptor`
 * so that one artifact can supply several related providers — `llm4s-openai`
 * contributes OpenAI, Azure, OpenRouter and Requesty from a single entry.
 *
 * Implementations must be a plain `class` with a public no-arg constructor,
 * '''not''' a Scala `object`: `java.util.ServiceLoader` instantiates the named
 * class, and an `object` exposes its instance as a `MODULE$` field instead.
 * Delegate to an `object` from the class if you want one:
 *
 * {{{
 * class MyProviderModule extends Llm4sProviderModule:
 *   def chatProviders: Seq[ProviderDescriptor] = Seq(MyProvider)
 * }}}
 *
 * Classpath discovery via `META-INF/services` arrives with PR 3 of
 * [[https://github.com/llm4s/llm4s/issues/1131 #1131]]; until then a module is
 * registered explicitly through `ProviderRegistry.of` or
 * `ProviderRegistry.withModule`.
 */
trait Llm4sProviderModule:

  /** The chat providers this module supplies. */
  def chatProviders: Seq[ProviderDescriptor] = Nil
