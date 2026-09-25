package org.llm4s.llmconnect.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.ServiceLoader
import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

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
 * Chat and embedding providers are held in separate namespaces, because the
 * two sets overlap without either containing the other - OpenAI and Ollama
 * supply both, Voyage only embeddings, Anthropic only chat. The same id may
 * therefore appear in both, and does: `ollama` names a chat client and an
 * embedding provider that share nothing but a base URL.
 *
 * Lookup is by `ProviderId` and never throws: an id nothing handles produces
 * a `Left` naming the ids that are registered, which is the difference between
 * a usable error and "provider openai not registered".
 *
 * @param descriptors          the registered chat providers, in registration order.
 * @param embeddingDescriptors the registered embedding providers, in registration order.
 * @param report               how this registry came to hold them; see [[ProviderRegistryReport]].
 */
final class ProviderRegistry private (
  val descriptors: Vector[ProviderDescriptor],
  val embeddingDescriptors: Vector[EmbeddingProviderDescriptor],
  val report: ProviderRegistryReport
):

  private val byId: Map[String, ProviderDescriptor] =
    descriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val byAlias: Map[String, ProviderDescriptor] =
    descriptors.flatMap { descriptor =>
      descriptor.aliases.map(alias => ProviderId(alias).asString -> descriptor)
    }.toMap

  private val embeddingById: Map[String, EmbeddingProviderDescriptor] =
    embeddingDescriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val embeddingByAlias: Map[String, EmbeddingProviderDescriptor] =
    embeddingDescriptors.flatMap { descriptor =>
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
    find(id).toRight(notRegistered("Provider", id, configPath, ids, "ProviderRegistry.of(...)"))

  /** The registered descriptor for `id`, or an error naming what is registered. */
  def get(id: ProviderId): Result[ProviderDescriptor] = resolve(id, None)

  /** The registered embedding descriptor for `id`, if any. Does not consider aliases. */
  def findEmbedding(id: ProviderId): Option[EmbeddingProviderDescriptor] = embeddingById.get(id.asString)

  /**
   * The registered embedding descriptor for `id`, or an error naming what is
   * registered.
   *
   * The error names the '''embedding''' providers specifically. A user who set
   * `EMBEDDING_MODEL=anthropic/...` needs to be told that Anthropic supplies no
   * embedding provider, not handed the chat list and left to infer it.
   *
   * @param configPath where the id came from, e.g. `"llm4s.embeddings.model"`.
   */
  def resolveEmbedding(id: ProviderId, configPath: Option[String] = None): Result[EmbeddingProviderDescriptor] =
    findEmbedding(id).toRight(
      // Not `ProviderRegistry.of`, which takes chat descriptors: following that advice for an
      // embedding provider is a compile error.
      notRegistered("Embedding provider", id, configPath, embeddingIds, "ProviderRegistry.ofEmbeddings(...)")
    )

  /** Registered chat provider ids in canonical spelling, sorted. */
  def ids: Seq[String] = byId.keys.toSeq.sorted

  /** Registered embedding provider ids in canonical spelling, sorted. */
  def embeddingIds: Seq[String] = embeddingById.keys.toSeq.sorted

  /**
   * The "not registered" error, carrying the discovery summary as well as the
   * registered ids.
   *
   * The two most common causes of a missing provider are invisible otherwise: a
   * dependency that was never added, and a fat jar whose `META-INF/services`
   * entries were dropped or overwritten during shading.
   *
   * @param remedy the registration call to suggest. The two halves have
   *               different ones, and suggesting the wrong one hands the user a
   *               compile error: `of` takes chat descriptors, `ofEmbeddings`
   *               embedding ones.
   */
  private def notRegistered(
    what: String,
    id: ProviderId,
    configPath: Option[String],
    registered: Seq[String],
    remedy: String
  ): ConfigurationError =
    val origin    = configPath.fold("")(path => s" (from $path)")
    val discovery = report.summary
    val scan      = if discovery.isEmpty then "" else s" $discovery"
    val known     = if registered.isEmpty then "none" else registered.mkString(", ")
    ConfigurationError(
      s"$what '${id.asString}'$origin is not registered. " +
        s"Registered ${what.toLowerCase}s: $known. " +
        s"If you expected '${id.asString}', add the dependency that supplies it, " +
        s"or register it explicitly with $remedy." + scan
    )

  /**
   * Folds an alias onto the id that owns it, leaving unknown strings alone.
   *
   * `"google"` becomes `"gemini"` because the Gemini descriptor (`llm4s-gemini`) declares that
   * alias. An id no provider claims is returned canonicalised but unchanged —
   * parsing config must not depend on what happens to be on the classpath; only
   * resolution does.
   */
  def canonicalId(raw: String): ProviderId =
    val id = ProviderId(raw)
    byAlias.get(id.asString).fold(id)(_.id)

  /** [[canonicalId]] over the embedding providers, which have their own aliases. */
  def canonicalEmbeddingId(raw: String): ProviderId =
    val id = ProviderId(raw)
    embeddingByAlias.get(id.asString).fold(id)(_.id)

  /**
   * This registry plus `descriptor`; a later registration of the same id wins.
   *
   * This is the escape hatch for a classpath where discovery cannot work — a
   * shaded fat jar, most often. The discovery report is carried through
   * unchanged, so the diagnostics still say what the scan saw.
   */
  def withProvider(descriptor: ProviderDescriptor): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors :+ descriptor, embeddingDescriptors, report)

  /** This registry plus `descriptor`; a later registration of the same id wins. */
  def withEmbeddingProvider(descriptor: EmbeddingProviderDescriptor): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors, embeddingDescriptors :+ descriptor, report)

  /** This registry plus every provider `module` supplies, of either kind. */
  def withModule(module: Llm4sProviderModule): ProviderRegistry =
    ProviderRegistry.fromDescriptors(
      descriptors ++ module.chatProviders,
      embeddingDescriptors ++ module.embeddingProviders,
      report
    )

  override def toString: String =
    val embeddings = if embeddingIds.isEmpty then "" else s"; embeddings: ${embeddingIds.mkString(", ")}"
    s"ProviderRegistry(${ids.mkString(", ")}$embeddings)"

object ProviderRegistry:

  private val logger = LoggerFactory.getLogger(classOf[ProviderRegistry])

  /** A registry holding exactly `descriptors`; a later duplicate id wins. */
  def of(descriptors: ProviderDescriptor*): ProviderRegistry =
    fromDescriptors(descriptors.toVector, Vector.empty, ProviderRegistryReport.explicit)

  /** A registry holding exactly `descriptors`; a later duplicate id wins. */
  def ofEmbeddings(descriptors: EmbeddingProviderDescriptor*): ProviderRegistry =
    fromDescriptors(Vector.empty, descriptors.toVector, ProviderRegistryReport.explicit)

  /** A registry holding everything `modules` supply, of either kind. */
  def ofModules(modules: Llm4sProviderModule*): ProviderRegistry =
    fromDescriptors(
      modules.toVector.flatMap(_.chatProviders),
      modules.toVector.flatMap(_.embeddingProviders),
      ProviderRegistryReport.explicit
    )

  private def fromDescriptors(
    descriptors: Vector[ProviderDescriptor],
    embeddingDescriptors: Vector[EmbeddingProviderDescriptor],
    report: ProviderRegistryReport
  ): ProviderRegistry =
    // Deduplicate by id keeping the *last* registration, so `withProvider` overrides rather
    // than silently losing to what is already there - a user-supplied descriptor must be able
    // to replace a built-in one of the same name.
    new ProviderRegistry(
      lastWins(descriptors)(_.id.asString),
      lastWins(embeddingDescriptors)(_.id.asString),
      report
    )

  private def lastWins[A](values: Vector[A])(key: A => String): Vector[A] =
    values.reverse.distinctBy(key).reverse

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
   * remaining provider. The loop below drives the iterator by hand and wraps
   * each step in [[guarded]], recording the failure in
   * [[ProviderRegistryReport]] instead.
   *
   * @param loader the class loader to scan; defaults to the thread's context
   *               class loader, falling back to this class's own loader when a
   *               context loader is not set.
   */
  def discover(loader: ClassLoader = defaultClassLoader): ProviderRegistry =
    val iterator = ServiceLoader.load(classOf[Llm4sProviderModule], loader).iterator()

    @tailrec
    def loop(scan: Scan): Scan =
      guarded("reading provider service entries")(iterator.hasNext) match
        case Left(failure) =>
          // `hasNext` is where the services files are parsed. A malformed one fails here and
          // leaves the iterator with nothing further to offer, so stop rather than spin on it.
          scan.failed(failure)

        case Right(false) =>
          scan

        case Right(true) =>
          guarded("loading a provider module")(iterator.next()) match
            case Left(failure) =>
              // A single unusable entry - a class that is absent, abstract, or has no public
              // no-arg constructor. `ServiceLoader` consumes it, so the scan continues.
              loop(scan.failed(failure))

            case Right(module) =>
              // Both lists are the module's own code, so both are guarded. Asking for them
              // separately means a module whose embedding half throws still contributes its
              // chat half, and the report says which half failed.
              val name = module.getClass.getName
              val chat = guarded(s"asking $name for its chat providers")(module.chatProviders.toVector)
              val embeddings =
                guarded(s"asking $name for its embedding providers")(module.embeddingProviders.toVector)

              val entry = ProviderModuleReport(
                moduleClass = name,
                providerIds = chat.toOption.getOrElse(Vector.empty).map(_.id.asString),
                embeddingProviderIds = embeddings.toOption.getOrElse(Vector.empty).map(_.id.asString),
                source = sourceOf(module)
              )
              loop(
                scan.copy(
                  descriptors = scan.descriptors ++ chat.getOrElse(Vector.empty),
                  embeddingDescriptors = scan.embeddingDescriptors ++ embeddings.getOrElse(Vector.empty),
                  modules = scan.modules :+ entry,
                  failures = scan.failures ++ chat.left.toSeq ++ embeddings.left.toSeq
                )
              )

    val scan   = loop(Scan())
    val report = ProviderRegistryReport(discovered = true, scan.modules, scan.failures)

    scan.failures.foreach(failure => logger.warn(s"Provider discovery: ${failure.detail}"))
    logger.debug(s"Provider discovery complete.\n${report.describe}")

    fromDescriptors(scan.descriptors, scan.embeddingDescriptors, report)

  /** What one pass of [[discover]] has accumulated so far. */
  final private case class Scan(
    descriptors: Vector[ProviderDescriptor] = Vector.empty,
    embeddingDescriptors: Vector[EmbeddingProviderDescriptor] = Vector.empty,
    modules: Vector[ProviderModuleReport] = Vector.empty,
    failures: Vector[ProviderDiscoveryFailure] = Vector.empty
  ):
    def failed(failure: ProviderDiscoveryFailure): Scan = copy(failures = failures :+ failure)

  /**
   * Runs one step of the scan, turning anything a provider module throws into a
   * recorded failure.
   *
   * The guard has to be wider than `scala.util.Try`, which catches only
   * `NonFatal` and so deliberately lets every `LinkageError` through. A
   * `LinkageError` is precisely what this boundary produces: a module compiled
   * against a different llm4s throws `AbstractMethodError` when its
   * `chatProviders` is called, and one whose own dependency is missing throws
   * `NoClassDefFoundError`. Either escaping would abort the initialisation of
   * `ProviderRegistry.default` and take every working provider with it - the
   * one failure mode discovery exists to prevent. `VirtualMachineError` and the
   * rest of the genuinely fatal set still propagate.
   *
   * @param what what was being attempted, used to build the failure's detail.
   */
  // scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
  private def guarded[A](what: => String)(body: => A): Either[ProviderDiscoveryFailure, A] =
    try Right(body)
    catch
      case error: LinkageError => Left(failureOf(what, error))
      case NonFatal(error)     => Left(failureOf(what, error))
  // scalafix:on DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch

  private def failureOf(what: String, error: Throwable): ProviderDiscoveryFailure =
    // The throwable's type is half the diagnosis at this boundary - `AbstractMethodError`
    // means version skew, `NoClassDefFoundError` a dependency that never arrived - and a
    // LinkageError's message is only a class name, so name the type either way.
    val detail =
      Option(error.getMessage).fold(error.getClass.getName)(message => s"${error.getClass.getName}: $message")
    ProviderDiscoveryFailure(s"$what failed: $detail", Some(error))

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
