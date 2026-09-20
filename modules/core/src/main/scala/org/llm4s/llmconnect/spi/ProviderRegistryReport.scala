package org.llm4s.llmconnect.spi

/**
 * One [[Llm4sProviderModule]] that classpath discovery loaded.
 *
 * @param moduleClass fully-qualified name of the module class.
 * @param providerIds the provider ids it contributed, in canonical spelling.
 * @param source      where the class was loaded from (a jar URL or a directory),
 *                    when the JVM can say. `None` for a class with no code source,
 *                    which is normal under some class loaders.
 */
final case class ProviderModuleReport(
  moduleClass: String,
  providerIds: Seq[String],
  source: Option[String]
)

/**
 * A service entry that discovery could not use.
 *
 * Recording these rather than letting them propagate is the point of the
 * hand-rolled iteration in [[ProviderRegistry.discover]]: one broken jar must
 * not take out every other provider on the classpath.
 *
 * @param detail  what went wrong, including the offending class name when
 *                `java.util.ServiceLoader` reported one.
 * @param failure the throwable, kept for logging rather than for control flow.
 */
final case class ProviderDiscoveryFailure(
  detail: String,
  failure: Option[Throwable] = None
)

/**
 * How a [[ProviderRegistry]] came to hold what it holds.
 *
 * This is the debuggability half of classpath discovery. "Provider openai is
 * not registered" is unusable on its own; the same message with "discovery
 * scanned 0 modules" says the services files did not survive shading, and with
 * "1 failed: ..." says which jar is broken.
 *
 * @param discovered whether `ServiceLoader` discovery ran at all. A registry
 *                   built by [[ProviderRegistry.of]] reports `false`, which is
 *                   different from discovery running and finding nothing.
 * @param modules    the modules discovery loaded, in the order it saw them.
 * @param failures   the service entries it could not use.
 */
final case class ProviderRegistryReport(
  discovered: Boolean,
  modules: Seq[ProviderModuleReport] = Nil,
  failures: Seq[ProviderDiscoveryFailure] = Nil
):

  /** True when any service entry failed to load. */
  def hasFailures: Boolean = failures.nonEmpty

  /**
   * One line describing the scan, suitable for appending to an error message.
   *
   * Empty for a registry that was built explicitly, which has no scan to
   * describe.
   */
  def summary: String =
    if !discovered then ""
    else
      val failureDetail =
        if failures.isEmpty then "0 failed"
        else s"${failures.size} failed: ${failures.map(_.detail).mkString("; ")}"
      s"Discovery scanned ${modules.size} ${if modules.size == 1 then "module" else "modules"}; $failureDetail."

  /** A multi-line description of every module and failure, for logs and diagnostics. */
  def describe: String =
    val header =
      if discovered then summary
      else s"Registry built explicitly; no classpath discovery ran."

    val moduleLines = modules.map { module =>
      val from = module.source.fold("")(source => s" [$source]")
      s"  - ${module.moduleClass}$from: ${
          if module.providerIds.isEmpty then "no providers"
          else module.providerIds.mkString(", ")
        }"
    }

    val failureLines = failures.map(failure => s"  ! ${failure.detail}")

    (header +: (moduleLines ++ failureLines)).mkString("\n")

object ProviderRegistryReport:

  /** The report for a registry that was built explicitly rather than discovered. */
  val explicit: ProviderRegistryReport = ProviderRegistryReport(discovered = false)
