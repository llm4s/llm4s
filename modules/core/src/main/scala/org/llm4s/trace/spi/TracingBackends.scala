package org.llm4s.trace.spi

import org.llm4s.trace.TracingMode
import org.slf4j.LoggerFactory

import java.util.{ Locale, ServiceLoader }
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * The tracing backends `Tracing.fromSettings` can dispatch to, keyed by mode.
 *
 * Usually obtained from [[TracingBackends.discover]], which reads
 * `META-INF/services/org.llm4s.trace.spi.TracingBackend` entries off the
 * classpath. When two backends claim the same mode the later registration wins,
 * so an explicit [[withBackend]] replaces a discovered one.
 *
 * @param failures   one line per service entry that could not be loaded, for
 *                   diagnosing a missing backend; empty when discovery was clean
 *                   or did not run.
 * @param discovered whether this set came from classpath discovery rather than
 *                   explicit registration.
 */
final class TracingBackends private (
  private val backends: Vector[TracingBackend],
  val failures: Vector[String],
  val discovered: Boolean
):

  /** The backend registered for `mode`, matched on [[TracingMode.name]] case-insensitively. */
  def find(mode: TracingMode): Option[TracingBackend] =
    val key = TracingBackends.keyOf(mode)
    backends.findLast(backend => TracingBackends.keyOf(backend.mode) == key)

  /** The modes a backend is registered for, in registration order. */
  def modes: Vector[TracingMode] = backends.map(_.mode).distinctBy(TracingBackends.keyOf)

  /** This set plus `backend`, which replaces any registered backend for the same mode. */
  def withBackend(backend: TracingBackend): TracingBackends =
    new TracingBackends(backends :+ backend, failures, discovered)

  override def toString: String =
    s"TracingBackends(${modes.map(_.name).mkString(", ")})"

object TracingBackends:

  private val logger = LoggerFactory.getLogger(classOf[TracingBackends])

  /** No backends at all: only `NoOp` and `Console`, which core builds itself, are usable. */
  val empty: TracingBackends = new TracingBackends(Vector.empty, Vector.empty, discovered = false)

  /** Exactly `backends`; a later backend for the same mode wins. */
  def of(backends: TracingBackend*): TracingBackends =
    new TracingBackends(backends.toVector, Vector.empty, discovered = false)

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
   * The scan runs each time it is called; `Tracing.create` calls it once per
   * tracer built, which is once per application in practice.
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
          // `mode` is the backend's own code, so it is asked for inside the guard: a backend
          // whose `mode` cannot be read cannot be dispatched to, and is recorded instead.
          guarded("loading a tracing backend")(iterator.next()).flatMap { backend =>
            guarded(s"asking ${backend.getClass.getName} for its mode")(keyOf(backend.mode)).map(_ => backend)
          } match
            case Left(failure)  => loop(found, failures :+ failure)
            case Right(backend) => loop(found :+ backend, failures)

    val (found, failures) = loop(Vector.empty, Vector.empty)
    failures.foreach(failure => logger.warn(s"Tracing backend discovery: $failure"))
    logger.debug(s"Tracing backend discovery found: ${found.map(b => s"${b.mode.name} -> ${b.getClass.getName}")}")

    new TracingBackends(found, failures, discovered = true)

  private[spi] def keyOf(mode: TracingMode): String = mode.name.toLowerCase(Locale.ROOT)

  /**
   * Runs one step of the scan, turning anything thrown into a recorded failure.
   * Wider than `Try`, which lets every `LinkageError` through; that is exactly
   * what a stale or half-present backend jar produces.
   */
  // scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
  private def guarded[A](what: => String)(body: => A): Either[String, A] =
    try Right(body)
    catch
      case error: LinkageError => Left(describe(what, error))
      case NonFatal(error)     => Left(describe(what, error))
  // scalafix:on DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch

  private def describe(what: String, error: Throwable): String =
    s"$what failed: ${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

  private def defaultClassLoader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader).getOrElse(classOf[TracingBackend].getClassLoader)
