package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable
import org.llm4s.error.{ ConfigurationError, TroubleshootingLinks }
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
 * `llm4s-core` ships no provider: every one comes from a provider module
 * (`llm4s-openai`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`,
 * `llm4s-openai-compatible`, `llm4s-voyage`, or a third party's), found through
 * its `META-INF/services` entry. Where discovery cannot run - a shaded fat jar
 * whose services files were merged away - [[ProviderRegistry.ofModules]] builds
 * the registry from the modules named explicitly.
 *
 * Chat and embedding providers are held in separate namespaces, because the
 * two sets overlap without either containing the other - OpenAI and Ollama
 * supply both, Voyage only embeddings, Anthropic only chat. The same id may
 * therefore appear in both, and does: `ollama` names a chat client and an
 * embedding provider that share nothing but a base URL.
 *
 * Lookup is by `ProviderId` or by an alias a descriptor declares, and never
 * throws: an id nothing handles produces a `Left` naming the ids that are
 * registered, which is the difference between a usable error and "provider
 * openai not registered". An id always beats an alias: an alias equal to
 * another provider's id is ignored, and reported in [[report]] as a collision,
 * as is an alias two providers declare (the later one keeps it).
 *
 * @param descriptors          the registered chat providers, in registration order.
 * @param embeddingDescriptors the registered embedding providers, in registration order.
 * @param report               how this registry came to hold them; see [[ProviderRegistryReport]].
 */
@Stable
final class ProviderRegistry private (
  val descriptors: Vector[ProviderDescriptor],
  val embeddingDescriptors: Vector[EmbeddingProviderDescriptor],
  val report: ProviderRegistryReport
):

  private val byId: Map[String, ProviderDescriptor] =
    descriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val byAlias: Map[String, ProviderDescriptor] =
    ProviderRegistry.aliasIndex(descriptors)(_.id.asString, _.aliases)

  private val embeddingById: Map[String, EmbeddingProviderDescriptor] =
    embeddingDescriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val embeddingByAlias: Map[String, EmbeddingProviderDescriptor] =
    ProviderRegistry.aliasIndex(embeddingDescriptors)(_.id.asString, _.aliases)

  /** The registered descriptor whose id or alias is `id`, if any; an id beats an alias. */
  def find(id: ProviderId): Option[ProviderDescriptor] = byId.get(id.asString).orElse(byAlias.get(id.asString))

  /**
   * The registered descriptor whose id or alias is `id`, or an error naming what is registered.
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

  /** The registered embedding descriptor whose id or alias is `id`, if any; an id beats an alias. */
  def findEmbedding(id: ProviderId): Option[EmbeddingProviderDescriptor] =
    embeddingById.get(id.asString).orElse(embeddingByAlias.get(id.asString))

  /**
   * The registered embedding descriptor whose id or alias is `id`, or an error
   * naming what is registered.
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
      TroubleshootingLinks.configuration(
        s"$what '${id.asString}'$origin is not registered. " +
          s"Registered ${what.toLowerCase}s: $known. " +
          s"If you expected '${id.asString}', add the dependency that supplies it, " +
          s"or register it explicitly with $remedy." + scan,
        TroubleshootingLinks.Entry.ProviderNotRegistered
      )
    )

  /**
   * Folds an alias onto the id that owns it, leaving unknown strings alone.
   *
   * `"google"` becomes `"gemini"` because the Gemini descriptor (`llm4s-gemini`) declares that
   * alias. A registered id stays itself even when another provider declares it as an alias. An
   * id no provider claims is returned canonicalised but unchanged — parsing config must not
   * depend on what happens to be on the classpath; only resolution does.
   */
  def canonicalId(raw: String): ProviderId =
    val id = ProviderId(raw)
    find(id).fold(id)(_.id)

  /** [[canonicalId]] over the embedding providers, which have their own aliases. */
  def canonicalEmbeddingId(raw: String): ProviderId =
    val id = ProviderId(raw)
    findEmbedding(id).fold(id)(_.id)

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
    // to replace a discovered one of the same name. Outside `discover()` there is no module to
    // blame a dropped id on, so collisions found here are reported against "explicit
    // registration"; `discover()` computes its own, module-attributed collisions instead of
    // going through this path.
    val (dedupedChat, chatCollisionIds)            = lastWins(descriptors)(_.id.asString)
    val (dedupedEmbeddings, embeddingCollisionIds) = lastWins(embeddingDescriptors)(_.id.asString)
    val idCollisions =
      chatCollisionIds.map(id => ProviderIdCollision("chat", id, "explicit registration", None)) ++
        embeddingCollisionIds.map(id => ProviderIdCollision("embedding", id, "explicit registration", None))
    // Alias collisions belong to the set as it now stands, so they are recomputed rather than
    // accumulated: replacing the provider that declared an alias can resolve one.
    val collisions = report.collisions.filterNot(isAliasCollision) ++ idCollisions ++
      aliasCollisions(dedupedChat, dedupedEmbeddings)
    build(
      dedupedChat,
      dedupedEmbeddings,
      if collisions == report.collisions then report else report.withCollisions(collisions)
    )

  private def build(
    descriptors: Vector[ProviderDescriptor],
    embeddingDescriptors: Vector[EmbeddingProviderDescriptor],
    report: ProviderRegistryReport
  ): ProviderRegistry =
    new ProviderRegistry(descriptors, embeddingDescriptors, report)

  private val ChatAlias      = "chat alias"
  private val EmbeddingAlias = "embedding alias"

  private def isAliasCollision(collision: ProviderIdCollision): Boolean =
    collision.kind == ChatAlias || collision.kind == EmbeddingAlias

  /**
   * Alias to descriptor, in canonical spelling. An alias equal to a registered id is left out,
   * because the id wins; of two descriptors declaring the same alias, the later one keeps it.
   */
  private[spi] def aliasIndex[D](
    descriptors: Vector[D]
  )(idOf: D => String, aliasesOf: D => Set[String]): Map[String, D] =
    val ids = descriptors.map(idOf).toSet
    aliasPairs(descriptors)(aliasesOf).filterNot((alias, _) => ids.contains(alias)).toMap

  private def aliasPairs[D](descriptors: Vector[D])(aliasesOf: D => Set[String]): Vector[(String, D)] =
    descriptors.flatMap(descriptor => aliasesOf(descriptor).toVector.sorted.map(ProviderId(_).asString -> descriptor))

  private def aliasCollisions(
    chat: Vector[ProviderDescriptor],
    embeddings: Vector[EmbeddingProviderDescriptor]
  ): Vector[ProviderIdCollision] =
    aliasCollisionsOf(chat, ChatAlias)(_.id.asString, _.aliases) ++
      aliasCollisionsOf(embeddings, EmbeddingAlias)(_.id.asString, _.aliases)

  /**
   * The aliases that do not resolve to the descriptor declaring them: one equal to another
   * provider's id (the id wins), and one an earlier descriptor also declared (the later wins).
   * `keptModule` and `droppedModule` name the providers, since one module can supply both.
   */
  private def aliasCollisionsOf[D](descriptors: Vector[D], kind: String)(
    idOf: D => String,
    aliasesOf: D => Set[String]
  ): Vector[ProviderIdCollision] =
    def provider(id: String) = s"provider '$id'"
    val ids                  = descriptors.map(idOf).toSet
    val pairs                = aliasPairs(descriptors)(aliasesOf).map((alias, descriptor) => alias -> idOf(descriptor))
    val shadowed = pairs.collect {
      case (alias, owner) if ids.contains(alias) && alias != owner =>
        ProviderIdCollision(kind, alias, provider(alias), Some(provider(owner)))
    }
    val shared = pairs
      .filterNot((alias, _) => ids.contains(alias))
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .flatMap { (alias, claims) =>
        val owners = claims.map(_._2).distinct
        Option.when(owners.sizeIs > 1)(
          ProviderIdCollision(kind, alias, provider(owners.last), Some(provider(owners(owners.size - 2))))
        )
      }
    shadowed ++ shared

  /** Deduplicates by `key`, last registration wins; also returns which keys collided. */
  private def lastWins[A](values: Vector[A])(key: A => String): (Vector[A], Vector[String]) =
    val duplicateKeys = values.groupBy(key).collect { case (k, vs) if vs.sizeIs > 1 => k }.toVector
    (values.reverse.distinctBy(key).reverse, duplicateKeys)

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
   * A descriptor whose `id` or `aliases` throws - one compiled against another
   * llm4s - is left out and recorded too; the module's other descriptors stay.
   *
   * @param loaders the class loaders to scan, in order. With none, the thread's
   *                context class loader and the loader of `llm4s-core` itself, so
   *                a context loader that cannot see the provider modules (some
   *                containers and test runners set one) does not hide them. A
   *                module seen through more than one loader counts once.
   */
  def discover(loaders: ClassLoader*): ProviderRegistry =
    val scanned = if loaders.isEmpty then defaultClassLoaders else loaders.distinct
    discoverFrom(scanned.toVector)

  private def discoverFrom(loaders: Vector[ClassLoader]): ProviderRegistry =
    val scan = loaders.foldLeft(Scan())((scan, loader) => scanLoader(loader, scan))

    val (dedupedChat, chatCollisionIds)            = lastWins(scan.descriptors)(_.id.asString)
    val (dedupedEmbeddings, embeddingCollisionIds) = lastWins(scan.embeddingDescriptors)(_.id.asString)
    val collisions =
      collisionsOf(scan.modules, chatCollisionIds, "chat", _.providerIds) ++
        collisionsOf(scan.modules, embeddingCollisionIds, "embedding", _.embeddingProviderIds) ++
        aliasCollisions(dedupedChat, dedupedEmbeddings)

    val report = ProviderRegistryReport(discovered = true, scan.modules, scan.failures, collisions)

    collisions.foreach { collision =>
      val dropped = collision.droppedModule.getOrElse("an earlier registration")
      logger.warn(
        s"Provider discovery: duplicate ${collision.subject} registered by both " +
          s"$dropped and ${collision.keptModule}; ${collision.keptModule} won."
      )
    }
    scan.failures.foreach(failure => logger.warn(s"Provider discovery: ${failure.detail}"))
    logger.debug(s"Provider discovery complete.\n${report.describe}")

    build(dedupedChat, dedupedEmbeddings, report)

  /** `scan` plus the modules `loader` finds that no earlier loader supplied. */
  private def scanLoader(loader: ClassLoader, start: Scan): Scan =
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

            case Right(module) if scan.seen.contains(module.getClass) =>
              // the same module class through a second loader (the context loader's parent, say)
              loop(scan)

            case Right(module) =>
              // Both lists are the module's own code, so both are guarded. Asking for them
              // separately means a module whose embedding half throws still contributes its
              // chat half, and the report says which half failed.
              val name = module.getClass.getName
              val chat = guarded(s"asking $name for its chat providers")(module.chatProviders.toVector)
                .map(usable(name, "chat", _)(_.id.asString, _.aliases))
              val embeddings =
                guarded(s"asking $name for its embedding providers")(module.embeddingProviders.toVector)
                  .map(usable(name, "embedding", _)(_.id.asString, _.aliases))

              val chatKept      = chat.map(_._1).getOrElse(Vector.empty)
              val embeddingKept = embeddings.map(_._1).getOrElse(Vector.empty)
              val entry = ProviderModuleReport(
                moduleClass = name,
                providerIds = chatKept.map(_._2),
                embeddingProviderIds = embeddingKept.map(_._2),
                source = sourceOf(module)
              )
              loop(
                scan.copy(
                  descriptors = scan.descriptors ++ chatKept.map(_._1),
                  embeddingDescriptors = scan.embeddingDescriptors ++ embeddingKept.map(_._1),
                  modules = scan.modules :+ entry,
                  failures = scan.failures ++ chat.left.toSeq ++ chat.toSeq.flatMap(_._2) ++
                    embeddings.left.toSeq ++ embeddings.toSeq.flatMap(_._2),
                  seen = scan.seen + module.getClass
                )
              )

    loop(start)

  /**
   * The descriptors whose `id` and `aliases` can be read, each with its id, and a failure for
   * each of the others. Both are a module's own code, so a version-skewed descriptor throws
   * here (`AbstractMethodError`) rather than later, inside the registry.
   */
  private def usable[D](module: String, kind: String, descriptors: Vector[D])(
    idOf: D => String,
    aliasesOf: D => Set[String]
  ): (Vector[(D, String)], Vector[ProviderDiscoveryFailure]) =
    val read = descriptors.zipWithIndex.map { (descriptor, index) =>
      guarded(s"reading the id and aliases of $module's $kind provider #${index + 1}") {
        val id = idOf(descriptor)
        aliasesOf(descriptor).foreach(ProviderId(_))
        descriptor -> id
      }
    }
    (read.collect { case Right(kept) => kept }, read.collect { case Left(failure) => failure })

  /** Turns duplicate ids into [[ProviderIdCollision]]s, naming the modules that supplied them. */
  private def collisionsOf(
    modules: Vector[ProviderModuleReport],
    duplicateIds: Vector[String],
    kind: String,
    idsOf: ProviderModuleReport => Seq[String]
  ): Vector[ProviderIdCollision] =
    duplicateIds.map { id =>
      val moduleNames = modules.filter(module => idsOf(module).contains(id)).map(_.moduleClass)
      val kept        = moduleNames.lastOption.getOrElse("unknown module")
      val dropped     = moduleNames.init.lastOption
      ProviderIdCollision(kind, id, kept, dropped)
    }

  /** What one pass of [[discover]] has accumulated so far. */
  final private case class Scan(
    descriptors: Vector[ProviderDescriptor] = Vector.empty,
    embeddingDescriptors: Vector[EmbeddingProviderDescriptor] = Vector.empty,
    modules: Vector[ProviderModuleReport] = Vector.empty,
    failures: Vector[ProviderDiscoveryFailure] = Vector.empty,
    seen: Set[Class[?]] = Set.empty
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
  private def guarded[A](what: => String)(body: => A): Either[ProviderDiscoveryFailure, A] =
    try Right(body)
    catch
      case error: LinkageError => Left(failureOf(what, error))
      case NonFatal(error)     => Left(failureOf(what, error))

  private def failureOf(what: String, error: Throwable): ProviderDiscoveryFailure =
    // The throwable's type is half the diagnosis at this boundary - `AbstractMethodError`
    // means version skew, `NoClassDefFoundError` a dependency that never arrived - and a
    // LinkageError's message is only a class name, so name the type either way.
    val detail =
      Option(error.getMessage).fold(error.getClass.getName)(message => s"${error.getClass.getName}: $message")
    ProviderDiscoveryFailure(s"$what failed: $detail", Some(error))

  private def sourceOf(module: Llm4sProviderModule): Option[String] =
    Try(Option(module.getClass.getProtectionDomain.getCodeSource).map(_.getLocation.toString)).toOption.flatten

  private def defaultClassLoaders: Seq[ClassLoader] =
    (Option(Thread.currentThread.getContextClassLoader).toSeq :+ classOf[Llm4sProviderModule].getClassLoader).distinct

  /**
   * The registry used when a caller supplies none: everything discovered on the
   * classpath, computed once.
   *
   * Discovery runs on first use rather than at class-load time, so an
   * application that always passes its own registry never pays for it.
   *
   * Discovery itself is I/O - a `ServiceLoader` scan of the classpath - so this
   * `lazy val` is a cached Action wearing the shape of Data: it reads the
   * classpath once per JVM and every later call sees that first answer, even if
   * the classpath could no longer produce it (a test that manipulates the
   * classpath after `default` has already run, say). That is the right choice
   * for a running application, where the classpath is fixed for the life of the
   * process. A caller that instead needs a fresh scan, or a deterministic
   * registry that does not depend on classpath order or on whichever other test
   * touched `default` first, should build one explicitly with
   * [[ProviderRegistry.of]] or [[ProviderRegistry.ofModules]] rather than rely
   * on this value.
   */
  lazy val default: ProviderRegistry = discover()

  /**
   * Resolves to `default` wherever a `using ProviderRegistry` is needed. Being
   * ambient does not change what `default` is: any code that picks this up
   * implicitly still gets the same cached, first-scan-wins registry, with the
   * same tradeoff described above.
   */
  given ProviderRegistry = default
