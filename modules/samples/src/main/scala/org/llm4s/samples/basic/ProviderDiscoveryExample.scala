package org.llm4s.samples.basic

import org.llm4s.llmconnect.provider.Llm4sOllamaModule
import org.llm4s.llmconnect.spi.ProviderRegistry

/**
 * Demonstrates provider discovery from the classpath via `ProviderRegistry.discover()`,
 * contrasting it with an explicit registry (`ProviderRegistry.ofModules`).
 *
 * == Context ==
 * Providers in LLM4S are discovered dynamically from the classpath. Each provider module
 * declares an implementation of `Llm4sProviderModule` in its `META-INF/services` file:
 * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`.
 *
 * Calling `ProviderRegistry.discover()` scans the classpath using `java.util.ServiceLoader` and
 * compiles a `ProviderRegistryReport` (`registry.report`) detailing:
 *  - `modules`: every `Llm4sProviderModule` loaded from the classpath.
 *  - `failures`: any service entries that could not be loaded or instantiated. A broken jar
 *    or missing dependency will be recorded here rather than throwing an unhandled exception,
 *    ensuring that other healthy providers continue to load.
 *  - `collisions`: instances where two modules register the same chat or embedding provider ID.
 *    LLM4S resolves collisions by letting the last-registered descriptor win, and logs the collision.
 *  - `summary`: a single-line summary of modules scanned, failures, and collisions.
 *  - `describe`: a human-readable multi-line report suitable for troubleshooting diagnostics.
 *
 * == When to use explicit registration ==
 * If your deployment uses a shaded or fat jar where `META-INF/services` files were overwritten
 * rather than merged, classpath discovery may find no provider modules or report missing providers.
 * In such environments, you can bypass discovery and register provider modules explicitly:
 * {{{
 * val registry = ProviderRegistry.ofModules(new Llm4sOllamaModule)
 * }}}
 * Explicit registries have `report.discovered == false` and contain only the descriptors
 * contributed by the specified modules.
 *
 * For detailed instructions on writing a provider or configuring shading merge strategies,
 * see `docs/guide/writing-a-provider.md`.
 *
 * To run:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.ProviderDiscoveryExample"
 * }}}
 */
object ProviderDiscoveryExample {

  final case class DiscoveryResult(
    discovered: ProviderRegistry,
    explicit: ProviderRegistry
  )

  /**
   * Discovers providers from the classpath and creates an explicit registry for comparison.
   *
   * @param classLoader optional ClassLoader to discover from (defaults to the thread's context class loader)
   * @return a [[DiscoveryResult]] containing both the discovered and explicit registries
   */
  def run(classLoader: ClassLoader = Thread.currentThread().getContextClassLoader): DiscoveryResult = {
    // 1. Classpath discovery: scans META-INF/services for Llm4sProviderModule implementations.
    // If a service file is broken, ServiceConfigurationErrors are recorded in report.failures
    // rather than terminating discovery. If multiple modules declare identical provider IDs,
    // the last one registered wins and report.collisions records both modules.
    val discovered = ProviderRegistry.discover(classLoader)

    // 2. Explicit registry: manually provides modules. Useful in shaded jars where
    // META-INF/services files were overwritten. report.discovered is false.
    // See docs/guide/writing-a-provider.md for details.
    val explicit = ProviderRegistry.ofModules(new Llm4sOllamaModule)

    DiscoveryResult(discovered, explicit)
  }

  /**
   * Formats the discovery result as human-readable lines for printing.
   */
  def render(result: DiscoveryResult): Seq[String] = {
    val discovered = result.discovered
    val explicit   = result.explicit

    Seq(
      "=== LLM4S Provider Discovery Sample ===",
      "",
      "--- 1. Classpath Discovered Registry ---",
      "Report:",
      discovered.report.describe,
      "",
      s"Chat Provider IDs (${discovered.ids.size}): ${discovered.ids.mkString(", ")}",
      s"Embedding Provider IDs (${discovered.embeddingIds.size}): ${discovered.embeddingIds.mkString(", ")}",
      "",
      "--- 2. Explicit Registry (Llm4sOllamaModule only) ---",
      s"Discovered: ${explicit.report.discovered}",
      s"Chat Provider IDs (${explicit.ids.size}): ${explicit.ids.mkString(", ")}",
      s"Embedding Provider IDs (${explicit.embeddingIds.size}): ${explicit.embeddingIds.mkString(", ")}",
      s"Summary: ${if (explicit.report.summary.isEmpty) "(empty for explicit registry)" else explicit.report.summary}",
      "",
      "--- 3. Troubleshooting Notes ---",
      "• Failures: recorded when a service entry in META-INF/services cannot be loaded or throws an exception.",
      "• Collisions: recorded when two modules register the same provider ID (last-registered wins).",
      "• Shaded jars: if 'provider X is not registered' occurs in a fat jar, verify META-INF/services merging.",
      "• Documentation: see docs/guide/writing-a-provider.md for provider authoring and assembly merge strategies."
    )
  }

  def main(args: Array[String]): Unit = {
    val result = run()
    render(result).foreach(println)
  }
}
