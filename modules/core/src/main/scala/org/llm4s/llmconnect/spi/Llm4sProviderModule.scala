package org.llm4s.llmconnect.spi

/**
 * A unit of provider registration: everything one module contributes.
 *
 * The discovered unit is a module rather than a single `ProviderDescriptor`
 * so that one artifact can supply several related providers — `llm4s-openai`
 * contributes OpenAI, Azure, Requesty and OpenAI embeddings from a single entry.
 *
 * Implementations must be a plain `class` with a public no-arg constructor,
 * '''not''' a Scala `object`: `java.util.ServiceLoader` instantiates the named
 * class, and an `object` exposes its instance as a `MODULE$` field instead.
 * Delegate to an `object` from the class if you want one:
 *
 * {{{
 * class MyProviderModule extends Llm4sProviderModule:
 *   override def chatProviders: Seq[ProviderDescriptor]                = Seq(MyProvider)
 *   override def embeddingProviders: Seq[EmbeddingProviderDescriptor]  = Seq(MyEmbeddings)
 * }}}
 *
 * Declare the class in
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` and
 * `ProviderRegistry.discover` finds it - adding the provider is then adding the
 * dependency, with no registration code at the call site. A module can also be
 * registered explicitly through `ProviderRegistry.ofModules` or
 * `ProviderRegistry.withModule`, which is the answer for a shaded fat jar whose
 * services files did not survive.
 */
trait Llm4sProviderModule:

  /** The chat providers this module supplies. */
  def chatProviders: Seq[ProviderDescriptor] = Nil

  /**
   * The embedding providers this module supplies.
   *
   * Both lists default to empty, so a module contributes whichever halves it
   * has: `llm4s-anthropic` overrides only `chatProviders`, a Voyage or Jina
   * module only `embeddingProviders`, and `llm4s-ollama` both.
   */
  def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Nil
