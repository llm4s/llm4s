package org.llm4s.llmconnect.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.ServiceLoader
import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

/**
 * The set of providers this build can resolve.
 *
 * A registry is an immutable value, not a mutable global: [[withProvider]] and
 * [[withModule]] return a new registry, and an application that wants a
 * different set constructs one with [[ProviderRegistry.of]] and passes it in.
 * Core code resolves a registry through a `using` clause, so the default is
 * `ProviderRegistry.default` — every provider on the classpath — unless the
 * caller supplies another.
 *
 * Lookup is by `ProviderId` and never throws: an id nothing handles produces
 * a `Left` naming the ids that are registered, which is the difference between
 * a usable error and "provider openai not registered".
 *
 * @param descriptors the registered providers, in registration order.
 * @param report      how this registry came to hold them; see [[ProviderRegistryReport]].
 */
final class ProviderRegistry private (
  val descriptors: Vector[ProviderDescriptor],
  val report: ProviderRegistryReport
):

  private val byId: Map[String, ProviderDescriptor] =
    descriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val byAlias: Map[String, ProviderDescriptor] =
    descriptors.flatMap { descriptor =>
      descriptor.aliases.map(alias => ProviderId(alias).asString -> descriptor)
    }.toMap

  /** The registered descriptor for `id`, if any. Does not consider aliases. */
  def find(id: ProviderId): Option[ProviderDescriptor] = byId.get(id.asString)

  /**
   * The registered descriptor for `id`, or an error naming what is registered.
   *
   * The error carries the discovery summary as well as the registered ids,
   * because the two most common causes of a missing provider are invisible
   * otherwise: a dependency that was never added, and a fat jar whose
   * `META-INF/services` entries were dropped or overwritten during shading.
   *
   * @param configPath where the id came from, e.g. `"llm4s.providers.my-bedrock.provider"`.
   *                   Included in the error so the user knows which entry to fix.
   */
  def resolve(id: ProviderId, configPath: Option[String] = None): Result[ProviderDescriptor] =
    find(id).toRight {
      val origin    = configPath.fold("")(path => s" (from $path)")
      val discovery = report.summary
      val scan      = if discovery.isEmpty then "" else s" $discovery"
      ConfigurationError(
        s"Provider '${id.asString}'$origin is not registered. " +
          s"Registered providers: ${ids.mkString(", ")}. " +
          s"If you expected '${id.asString}', add the dependency that supplies it, " +
          s"or register it explicitly with ProviderRegistry.of(...)." + scan
      )
    }

  /** The registered descriptor for `id`, or an error naming what is registered. */
  def get(id: ProviderId): Result[ProviderDescriptor] = resolve(id, None)

  /** Registered provider ids in canonical spelling, sorted. */
  def ids: Seq[String] = byId.keys.toSeq.sorted

  /**
   * Folds an alias onto the id that owns it, leaving unknown strings alone.
   *
   * `"google"` becomes `"gemini"` because the Gemini descriptor declares that
   * alias. An id no provider claims is returned canonicalised but unchanged —
   * parsing config must not depend on what happens to be on the classpath; only
   * resolution does.
   */
  def canonicalId(raw: String): ProviderId =
    val id = ProviderId(raw)
    byAlias.get(id.asString).fold(id)(_.id)

  /**
   * This registry plus `descriptor`; a later registration of the same id wins.
   *
   * This is the escape hatch for a classpath where discovery cannot work — a
   * shaded fat jar, most often. The discovery report is carried through
   * unchanged, so the diagnostics still say what the scan saw.
   */
  def withProvider(descriptor: ProviderDescriptor): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors :+ descriptor, report)

  /** This registry plus every provider `module` supplies. */
  def withModule(module: Llm4sProviderModule): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors ++ module.chatProviders, report)

  override def toString: String = s"ProviderRegistry(${ids.mkString(", ")})"

object ProviderRegistry:

  private val logger = LoggerFactory.getLogger(classOf[ProviderRegistry])

  /** A registry holding exactly `descriptors`; a later duplicate id wins. */
  def of(descriptors: ProviderDescriptor*): ProviderRegistry =
    fromDescriptors(descriptors.toVector, ProviderRegistryReport.explicit)

  /** A registry holding everything `modules` supply. */
  def ofModules(modules: Llm4sProviderModule*): ProviderRegistry =
    fromDescriptors(modules.toVector.flatMap(_.chatProviders), ProviderRegistryReport.explicit)

  private def fromDescriptors(
    descriptors: Vector[ProviderDescriptor],
    report: ProviderRegistryReport
  ): ProviderRegistry =
    // Deduplicate by id keeping the *last* registration, so `withProvider` overrides rather
    // than silently losing to what is already there - a user-supplied descriptor must be able
    // to replace a built-in one of the same name.
    val deduplicated =
      descriptors.reverse.distinctBy(_.id.asString).reverse
    new ProviderRegistry(deduplicated, report)

  /**
   * Every provider on `loader`'s classpath, found through `META-INF/services`.
   *
   * A provider module declares itself in
   * `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, so adding
   * a provider is adding a dependency — no registration code at the call site.
   *
   * '''This never throws, and one broken jar cannot take out the others.'''
   * `ServiceLoader`'s own iterator throws `ServiceConfigurationError` for a
   * service entry it cannot load, and the `for`-comprehension over it that you
   * would naturally write propagates the first such error and abandons every
   * remaining provider. The loop below drives the iterator by hand and guards
   * each step, recording the failure in [[ProviderRegistryReport]] instead.
   *
   * @param loader the class loader to scan; defaults to the thread's context
   *               class loader, falling back to this class's own loader when a
   *               context loader is not set.
   */
  def discover(loader: ClassLoader = defaultClassLoader): ProviderRegistry =
    val iterator = ServiceLoader.load(classOf[Llm4sProviderModule], loader).iterator()

    @tailrec
    def loop(
      descriptors: Vector[ProviderDescriptor],
      modules: Vector[ProviderModuleReport],
      failures: Vector[ProviderDiscoveryFailure]
    ): (Vector[ProviderDescriptor], Vector[ProviderModuleReport], Vector[ProviderDiscoveryFailure]) =
      Try(iterator.hasNext) match
        case Failure(error) =>
          // `hasNext` is where the services files are parsed. A malformed one fails here and
          // leaves the iterator with nothing further to offer, so stop rather than spin on it.
          (descriptors, modules, failures :+ failureOf("reading provider service entries", error))

        case Success(false) =>
          (descriptors, modules, failures)

        case Success(true) =>
          Try(iterator.next()) match
            case Failure(error) =>
              // A single unusable entry - a class that is absent, abstract, or has no public
              // no-arg constructor. `ServiceLoader` consumes it, so the scan continues.
              loop(descriptors, modules, failures :+ failureOf("loading a provider module", error))

            case Success(module) =>
              Try(module.chatProviders.toVector) match
                case Failure(error) =>
                  loop(
                    descriptors,
                    modules,
                    failures :+ failureOf(s"asking ${module.getClass.getName} for its providers", error)
                  )
                case Success(provided) =>
                  val entry = ProviderModuleReport(
                    moduleClass = module.getClass.getName,
                    providerIds = provided.map(_.id.asString),
                    source = sourceOf(module)
                  )
                  loop(descriptors ++ provided, modules :+ entry, failures)

    val (descriptors, modules, failures) = loop(Vector.empty, Vector.empty, Vector.empty)
    val report                           = ProviderRegistryReport(discovered = true, modules, failures)

    failures.foreach(failure => logger.warn(s"Provider discovery: ${failure.detail}"))
    logger.debug(s"Provider discovery complete.\n${report.describe}")

    fromDescriptors(descriptors, report)

  private def failureOf(what: String, error: Throwable): ProviderDiscoveryFailure =
    val message = Option(error.getMessage).getOrElse(error.getClass.getName)
    ProviderDiscoveryFailure(s"$what failed: $message", Some(error))

  private def sourceOf(module: Llm4sProviderModule): Option[String] =
    Try(Option(module.getClass.getProtectionDomain.getCodeSource).map(_.getLocation.toString)).toOption.flatten

  private def defaultClassLoader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader)
      .getOrElse(classOf[Llm4sProviderModule].getClassLoader)

  /**
   * The providers built into `llm4s-core`, without consulting the classpath.
   *
   * Use this when discovery cannot work — a shaded fat jar whose
   * `META-INF/services` entries were merged away is the usual reason — or when
   * an application wants exactly these providers and nothing a dependency might
   * add.
   */
  lazy val builtin: ProviderRegistry =
    ofModules(org.llm4s.llmconnect.provider.BuiltinProviders)

  /**
   * The registry used when a caller supplies none: everything discovered on the
   * classpath, computed once.
   *
   * Discovery runs on first use rather than at class-load time, so an
   * application that always passes its own registry never pays for it.
   */
  lazy val default: ProviderRegistry = discover()

  /** Resolves to `default` wherever a `using ProviderRegistry` is needed. */
  given ProviderRegistry = default
