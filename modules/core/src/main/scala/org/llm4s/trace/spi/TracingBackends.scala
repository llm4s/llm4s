package org.llm4s.trace.spi

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.{ Locale, ServiceLoader }
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * The tracing backends `Tracing.fromSettings` can dispatch to, keyed by mode.
 *
 * Usually obtained from [[TracingBackends.discover]], which reads
 * `META-INF/services/org.llm4s.trace.spi.TracingBackend` entries off the
 * classpath. A backend answers to its [[TracingBackend.mode mode]] and to each of
 * its [[TracingBackend.aliases aliases]].
 *
 * Explicit registration replaces: [[withBackend]], and a later argument of
 * [[TracingBackends.of]], takes the place of a backend already registered for the
 * same mode. Nothing else is resolved by order. Two backends that both answer to a
 * name - two discovered for one mode, or one whose alias is another's mode or
 * alias - make [[find]] fail for that name, naming both, rather than let the order
 * of the classpath pick one.
 *
 * @param failures   one line per service entry that could not be loaded, for
 *                   diagnosing a missing backend; empty when discovery was clean
 *                   or did not run.
 * @param discovered whether this set came from classpath discovery rather than
 *                   explicit registration.
 */
@Stable
final class TracingBackends private (
  private val backends: Vector[TracingBackend],
  val failures: Vector[String],
  val discovered: Boolean
):

  /**
   * The backend that answers to `mode`: the one whose [[TracingBackend.mode mode]] or one of whose
   * [[TracingBackend.aliases aliases]] is its [[org.llm4s.trace.TracingMode.name name]], case-insensitively.
   *
   * @return the backend, `None` when no backend answers to the name, or a
   *         [[org.llm4s.error.ConfigurationError ConfigurationError]] naming each backend when more than one does
   */
  def find(mode: TracingMode): Result[Option[TracingBackend]] =
    val key = TracingBackends.keyOf(mode)
    backends.filter(backend => TracingBackends.namesOf(backend).contains(key)) match
      case Vector()    => Right(None)
      case Vector(one) => Right(Some(one))
      case several =>
        val claimants = several.map(b => s"${b.getClass.getName} (mode '${TracingBackends.keyOf(b.mode)}')")
        Left(
          ConfigurationError(
            s"Tracing mode '$key' is claimed by more than one TracingBackend: ${claimants.mkString(", ")}. " +
              "Remove one from the classpath, or pass the one you want to Tracing.fromSettings in a " +
              "TracingBackends.of(...)."
          )
        )

  /** The modes a backend is registered for, in registration order. */
  def modes: Vector[TracingMode] = backends.map(_.mode).distinctBy(TracingBackends.keyOf)

  /**
   * This set plus `backend`, which replaces any registered backend for the same mode. A registered backend of
   * another mode that answers to one of `backend`'s names stays, and [[find]] reports the two for that name.
   */
  def withBackend(backend: TracingBackend): TracingBackends =
    val key = TracingBackends.keyOf(backend.mode)
    new TracingBackends(
      backends.filterNot(b => TracingBackends.keyOf(b.mode) == key) :+ backend,
      failures,
      discovered
    )

  override def toString: String =
    s"TracingBackends(${modes.map(_.name).mkString(", ")})"

object TracingBackends:

  private val logger = LoggerFactory.getLogger(classOf[TracingBackends])

  /** No backends at all: only `NoOp` and `Console`, which core builds itself, are usable. */
  val empty: TracingBackends = new TracingBackends(Vector.empty, Vector.empty, discovered = false)

  /** Exactly `backends`; a later backend replaces an earlier one for the same mode. */
  def of(backends: TracingBackend*): TracingBackends =
    backends.foldLeft(empty)((set, backend) => set.withBackend(backend))

  /**
   * Every tracing backend on `loader`'s classpath, found through `META-INF/services`.
   *
   * '''This never throws, and one broken jar cannot take out the others.'''
   * `ServiceLoader`'s iterator throws `ServiceConfigurationError` for an entry it
   * cannot load, and a backend compiled against another llm4s throws a
   * `LinkageError` that `scala.util.Try` does not catch. Each step is guarded and
   * a failure is recorded in [[TracingBackends.failures]] instead - the same
   * contract as `ProviderRegistry.discover`.
   *
   * The scan runs each time it is called: `Llm4sConfig.tracing` and `Tracing.fromSettings`
   * each call it once for a mode core does not build itself, which is once per application in practice.
   *
   * @param loader the class loader to scan; defaults to the thread's context
   *               class loader, falling back to this class's own loader.
   */
  def discover(loader: ClassLoader = defaultClassLoader): TracingBackends =
    val iterator = ServiceLoader.load(classOf[TracingBackend], loader).iterator()

    @tailrec
    def loop(found: Vector[TracingBackend], failures: Vector[String]): (Vector[TracingBackend], Vector[String]) =
      guarded("reading tracing backend service entries")(iterator.hasNext) match
        case Left(failure) =>
          // `hasNext` is where a services file is parsed; a malformed one leaves nothing further.
          (found, failures :+ failure)
        case Right(false) =>
          (found, failures)
        case Right(true) =>
          // `mode` and `aliases` are the backend's own code, so they are asked for inside the
          // guard: a backend whose names cannot be read cannot be dispatched to, and is recorded instead.
          guarded("loading a tracing backend")(iterator.next()).flatMap { backend =>
            guarded(s"asking ${backend.getClass.getName} for its mode and aliases")(namesOf(backend)).map(_ => backend)
          } match
            case Left(failure)  => loop(found, failures :+ failure)
            case Right(backend) => loop(found :+ backend, failures)

    val (found, failures) = loop(Vector.empty, Vector.empty)
    failures.foreach(failure => logger.warn(s"Tracing backend discovery: $failure"))
    logger.debug(s"Tracing backend discovery found: ${found.map(b => s"${b.mode.name} -> ${b.getClass.getName}")}")

    new TracingBackends(found, failures, discovered = true)

  private[spi] def keyOf(mode: TracingMode): String = mode.name.toLowerCase(Locale.ROOT)

  /** Every name `backend` answers to: its mode and its aliases, lower-cased. */
  private[spi] def namesOf(backend: TracingBackend): Set[String] =
    backend.aliases.map(_.trim.toLowerCase(Locale.ROOT)) + keyOf(backend.mode)

  /**
   * Runs one step of the scan, turning anything thrown into a recorded failure.
   * Wider than `Try`, which lets every `LinkageError` through; that is exactly
   * what a stale or half-present backend jar produces.
   */
  private def guarded[A](what: => String)(body: => A): Either[String, A] =
    try Right(body)
    catch
      case error: LinkageError => Left(describe(what, error))
      case NonFatal(error)     => Left(describe(what, error))

  private def describe(what: String, error: Throwable): String =
    s"$what failed: ${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

  private def defaultClassLoader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader).getOrElse(classOf[TracingBackend].getClassLoader)
