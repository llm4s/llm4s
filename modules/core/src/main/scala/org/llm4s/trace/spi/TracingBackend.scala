package org.llm4s.trace.spi

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.{ Tracing, TracingMode }
import org.llm4s.types.Result

/**
 * A tracing backend: what one integration contributes to `Tracing.create`.
 *
 * `Tracing.create` builds the two backends that need nothing beyond core -
 * `NoOp` and `Console` - itself, and hands every other [[TracingMode]] to the
 * backend registered for it. Adding a backend is then adding a dependency, with
 * no edit to core: the same arrangement slice 4 gave providers (#1131, #1133).
 *
 * Implementations must be a plain `class` with a public no-arg constructor,
 * '''not''' a Scala `object`: `java.util.ServiceLoader` instantiates the named
 * class, and an `object` exposes its instance as a `MODULE$` field instead.
 *
 * {{{
 * class DatadogTracingBackend extends TracingBackend:
 *   val mode: TracingMode = TracingMode.Named("datadog")
 *   def create(settings: TracingSettings): Result[Tracing] = Right(new DatadogTracing(...))
 * }}}
 *
 * Declare the class in `META-INF/services/org.llm4s.trace.spi.TracingBackend`
 * and `TRACING_MODE=datadog` selects it. A backend can also be registered
 * explicitly through [[TracingBackends.of]] or [[TracingBackends.withBackend]]
 * and passed to `Tracing.fromSettings`, which is the answer for a shaded fat jar
 * whose services files did not survive.
 */
trait TracingBackend:

  /**
   * The mode this backend serves. A backend outside core uses
   * `TracingMode.Named`; matching is by [[TracingMode.name]], case-insensitively.
   */
  def mode: TracingMode

  /**
   * Builds the tracer for `settings`.
   *
   * Return a `Left` rather than throwing when the backend cannot start - a
   * missing credential, an unreachable collector that fails eagerly - so that
   * `Tracing.fromSettings` can report it. A thrown exception is caught and
   * reported the same way, but loses the chance to say what went wrong.
   */
  def create(settings: TracingSettings): Result[Tracing]
