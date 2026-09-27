package org.llm4s.trace

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

/**
 * Registers [[LangfuseTracing]] for `TracingMode.Langfuse` (`TRACING_MODE=langfuse`).
 *
 * Declared in core's `META-INF/services/org.llm4s.trace.spi.TracingBackend`. This
 * file and that entry leave core together when Langfuse is carved into
 * `llm4s-observability` (#1133), after which `TRACING_MODE=langfuse` without that
 * module gives `NoOpTracing` and an error, as OpenTelemetry already does.
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates it through a public no-arg constructor.
 */
final class LangfuseTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.Langfuse

  def create(settings: TracingSettings): Result[Tracing] =
    Right(LangfuseTracing.from(settings.langfuse))
