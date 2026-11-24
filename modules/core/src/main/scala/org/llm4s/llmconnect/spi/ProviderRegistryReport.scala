package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * One [[Llm4sProviderModule]] that classpath discovery loaded.
 *
 * @param moduleClass          fully-qualified name of the module class.
 * @param providerIds          the chat provider ids it contributed, in canonical spelling.
 * @param embeddingProviderIds the embedding provider ids it contributed.
 * @param source               where the class was loaded from (a jar URL or a directory),
 *                             when the JVM can say. `None` for a class with no code source,
 *                             which is normal under some class loaders.
 */
@Stable
final case class ProviderModuleReport private (
  moduleClass: String,
  providerIds: Seq[ProviderId],
  source: Option[String],
  embeddingProviderIds: Seq[ProviderId]
):

  /** How this module's contribution reads in [[ProviderRegistryReport.describe]]. */
  def contribution: String =
    val chat = if providerIds.isEmpty then Nil else Seq(providerIds.map(_.asString).mkString(", "))
    val embedding =
      if embeddingProviderIds.isEmpty then Nil
      else Seq(s"embeddings: ${embeddingProviderIds.map(_.asString).mkString(", ")}")
    if chat.isEmpty && embedding.isEmpty then "no providers" else (chat ++ embedding).mkString("; ")

object ProviderModuleReport:

  /** Creates a [[ProviderModuleReport]]. */
  private[llm4s] def apply(
    moduleClass: String,
    providerIds: Seq[ProviderId],
    source: Option[String],
    embeddingProviderIds: Seq[ProviderId] = Nil
  ): ProviderModuleReport =
    new ProviderModuleReport(moduleClass, providerIds, source, embeddingProviderIds)

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
@Stable
final case class ProviderDiscoveryFailure private (
  detail: String,
  failure: Option[Throwable]
)

object ProviderDiscoveryFailure:

  /** Creates a [[ProviderDiscoveryFailure]]. */
  private[llm4s] def apply(detail: String, failure: Option[Throwable] = None): ProviderDiscoveryFailure =
    new ProviderDiscoveryFailure(detail, failure)

/**
 * A chat or embedding provider id two descriptors both registered; the later
 * one won. Or an alias that does not reach the descriptor declaring it,
 * because it is another provider's id (the id wins) or an earlier descriptor
 * declared it too (the later one wins).
 *
 * `ProviderRegistry` has always resolved this by keeping the last
 * registration, silently - this is what makes that resolution visible instead
 * of invisible.
 *
 * @param kind          `"chat"` or `"embedding"` for an id; `"chat alias"` or
 *                       `"embedding alias"` for an alias.
 * @param id            the id or alias two descriptors both claimed.
 * @param keptModule    the module (or `"explicit registration"`) whose
 *                       descriptor won; for an alias, the provider that kept
 *                       it (`"provider 'gemini'"`).
 * @param droppedModule the module that lost, when discovery can name it; for
 *                       an alias, the provider that lost it.
 */
@Stable
final case class ProviderIdCollision private (
  kind: ProviderCollisionKind,
  id: ProviderId,
  keptModule: String,
  droppedModule: Option[String]
):

  /** What collided, for a message: `chat id 'acme'` or `chat alias 'google'`. */
  private[llm4s] def subject: String =
    if kind.isAlias then s"${kind.label} '${id.asString}'" else s"${kind.label} id '${id.asString}'"

object ProviderIdCollision:

  /** Creates a [[ProviderIdCollision]]. */
  private[llm4s] def apply(
    kind: ProviderCollisionKind,
    id: ProviderId,
    keptModule: String,
    droppedModule: Option[String]
  ): ProviderIdCollision =
    new ProviderIdCollision(kind, id, keptModule, droppedModule)

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
 * @param collisions the ids two descriptors both registered.
 */
@Stable
final case class ProviderRegistryReport private (
  discovered: Boolean,
  modules: Seq[ProviderModuleReport],
  failures: Seq[ProviderDiscoveryFailure],
  collisions: Seq[ProviderIdCollision]
):

  private[llm4s] def withCollisions(collisions: Seq[ProviderIdCollision]): ProviderRegistryReport =
    copy(collisions = collisions)

  /** True when any service entry failed to load. */
  def hasFailures: Boolean = failures.nonEmpty

  /** True when two descriptors registered the same id, or an alias does not reach its descriptor. */
  def hasCollisions: Boolean = collisions.nonEmpty

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
      val collisionDetail = if collisions.isEmpty then "" else s" ${collisions.size} id collision(s)."
      s"Discovery scanned ${modules.size} ${if modules.size == 1 then "module" else "modules"}; $failureDetail.$collisionDetail"

  /** A multi-line description of every module, failure and collision, for logs and diagnostics. */
  def describe: String =
    val header =
      if discovered then summary
      else s"Registry built explicitly; no classpath discovery ran."

    val moduleLines = modules.map { module =>
      val from = module.source.fold("")(source => s" [$source]")
      s"  - ${module.moduleClass}$from: ${module.contribution}"
    }

    val failureLines = failures.map(failure => s"  ! ${failure.detail}")

    val collisionLines = collisions.map { collision =>
      val dropped = collision.droppedModule.getOrElse("an earlier registration")
      s"  * ${collision.subject} registered by both $dropped and ${collision.keptModule}; ${collision.keptModule} won."
    }

    (header +: (moduleLines ++ failureLines ++ collisionLines)).mkString("\n")

object ProviderRegistryReport:

  /** Creates a [[ProviderRegistryReport]]. */
  private[llm4s] def apply(
    discovered: Boolean,
    modules: Seq[ProviderModuleReport] = Nil,
    failures: Seq[ProviderDiscoveryFailure] = Nil,
    collisions: Seq[ProviderIdCollision] = Nil
  ): ProviderRegistryReport =
    new ProviderRegistryReport(discovered, modules, failures, collisions)

  /** The report for a registry that was built explicitly rather than discovered. */
  val explicit: ProviderRegistryReport = ProviderRegistryReport(discovered = false)

/**
 * What two registrations collided over: a provider id or an alias, of a chat or an embedding provider.
 *
 * @param label how a message names it, e.g. `chat` or `embedding alias`
 */
@Stable
enum ProviderCollisionKind(val label: String):
  case Chat           extends ProviderCollisionKind("chat")
  case Embedding      extends ProviderCollisionKind("embedding")
  case ChatAlias      extends ProviderCollisionKind("chat alias")
  case EmbeddingAlias extends ProviderCollisionKind("embedding alias")

  /** Whether the collision is over an alias, not an id. */
  def isAlias: Boolean = this == ChatAlias || this == EmbeddingAlias
