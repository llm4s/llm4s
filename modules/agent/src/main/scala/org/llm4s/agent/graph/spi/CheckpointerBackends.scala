package org.llm4s.agent.graph.spi

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.{ Locale, ServiceLoader }
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * The checkpoint store backends `CheckpointerConfigLoader.open` can dispatch to, keyed by kind.
 *
 * Usually obtained from [[CheckpointerBackends.discover]], which reads
 * `META-INF/services/org.llm4s.agent.graph.spi.CheckpointerBackend` entries off the classpath. The `memory` kind is
 * always available, an [[org.llm4s.agent.graph.InMemoryCheckpointer InMemoryCheckpointer]] on the system clock, unless
 * a registered backend claims it. When two backends claim one kind the later registration wins, so an explicit
 * [[withBackend]] replaces a discovered one.
 *
 * @param failures   one line per service entry that could not be loaded, for diagnosing a missing backend; empty when
 *                   discovery was clean or did not run
 * @param discovered whether this set came from classpath discovery rather than explicit registration
 */
@Experimental
final class CheckpointerBackends private (
  private val backends: Vector[CheckpointerBackend],
  val failures: Vector[String],
  val discovered: Boolean
):

  /** The backend for `kind`, matched case-insensitively; the built-in `memory` one if none claims `memory`. */
  def find(kind: String): Option[CheckpointerBackend] =
    val key = CheckpointerBackends.keyOf(kind)
    backends
      .findLast(backend => CheckpointerBackends.keyOf(backend.kind) == key)
      .orElse(Option.when(key == CheckpointerBackends.MemoryKind)(CheckpointerBackends.InMemoryBackend))

  /** The kinds a backend is available for: `memory`, then the registered ones in registration order. */
  def kinds: Vector[String] =
    (CheckpointerBackends.MemoryKind +: backends.map(b => CheckpointerBackends.keyOf(b.kind))).distinct

  /** This set plus `backend`, which replaces any backend for the same kind. */
  def withBackend(backend: CheckpointerBackend): CheckpointerBackends =
    new CheckpointerBackends(backends :+ backend, failures, discovered)

  override def toString: String = s"CheckpointerBackends(${kinds.mkString(", ")})"

object CheckpointerBackends:

  private val logger = LoggerFactory.getLogger(classOf[CheckpointerBackends])

  /** The kind `llm4s-agent` serves itself. */
  val MemoryKind: String = "memory"

  /**
   * The module that provides each store kind this repository ships, so that a load naming a kind whose module is
   * absent can say which dependency to add.
   */
  private[llm4s] val KnownModules: Map[String, String] = Map(
    "sqlite"   -> "llm4s-agent-checkpoint-sqlite",
    "postgres" -> "llm4s-agent-checkpoint-postgres"
  )

  /** Only the built-in `memory` backend. */
  val empty: CheckpointerBackends = new CheckpointerBackends(Vector.empty, Vector.empty, discovered = false)

  /** Exactly `backends`, plus the built-in `memory` one unless one of them claims it; a later one for a kind wins. */
  def of(backends: CheckpointerBackend*): CheckpointerBackends =
    new CheckpointerBackends(backends.toVector, Vector.empty, discovered = false)

  /**
   * Every checkpoint store backend on `loader`'s classpath, found through `META-INF/services`.
   *
   * '''This never throws, and one broken jar cannot take out the others''': an entry that cannot be loaded, or whose
   * `kind` cannot be read, is recorded in [[CheckpointerBackends.failures]] and logged at WARN, as
   * `TracingBackends.discover` does.
   *
   * @param loader the class loader to scan; the thread's context class loader by default, else this class's own
   */
  def discover(loader: ClassLoader = defaultClassLoader): CheckpointerBackends =
    val iterator = ServiceLoader.load(classOf[CheckpointerBackend], loader).iterator()

    @tailrec
    def loop(
      found: Vector[CheckpointerBackend],
      failures: Vector[String]
    ): (Vector[CheckpointerBackend], Vector[String]) =
      guarded("reading checkpointer backend service entries")(iterator.hasNext) match
        case Left(failure) => (found, failures :+ failure)
        case Right(false)  => (found, failures)
        case Right(true) =>
          guarded("loading a checkpointer backend")(iterator.next()).flatMap { backend =>
            guarded(s"asking ${backend.getClass.getName} for its kind")(keyOf(backend.kind)).map(_ => backend)
          } match
            case Left(failure)  => loop(found, failures :+ failure)
            case Right(backend) => loop(found :+ backend, failures)

    val (found, failures) = loop(Vector.empty, Vector.empty)
    failures.foreach(failure => logger.warn(s"Checkpointer backend discovery: $failure"))
    logger.debug(s"Checkpointer backend discovery found: ${found.map(b => s"${b.kind} -> ${b.getClass.getName}")}")
    new CheckpointerBackends(found, failures, discovered = true)

  private[spi] def keyOf(kind: String): String = kind.trim.toLowerCase(Locale.ROOT)

  /** The `memory` kind: a new in-memory store on the system clock; its block is not read. */
  private object InMemoryBackend extends CheckpointerBackend:
    val kind: String = MemoryKind
    def open(settings: CheckpointerSettings): Result[CheckpointStore] =
      Right(CheckpointStore.unmanaged(kind, new InMemoryCheckpointer()))

  /** One step of the scan, anything thrown (a `LinkageError` from a stale jar included) becoming a failure. */
  private def guarded[A](what: => String)(body: => A): Either[String, A] =
    try Right(body)
    catch
      case error: LinkageError => Left(describe(what, error))
      case NonFatal(error)     => Left(describe(what, error))

  private def describe(what: String, error: Throwable): String =
    s"$what failed: ${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

  private def defaultClassLoader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader).getOrElse(classOf[CheckpointerBackend].getClassLoader)
