package org.llm4s.trace

import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

/**
 * Registers [[OpenTelemetryTracing]] for `TracingMode.OpenTelemetry`
 * (`TRACING_MODE=opentelemetry` or `otel`).
 *
 * Declared in this module's `META-INF/services/org.llm4s.trace.spi.TracingBackend`,
 * so depending on `llm4s-observability-otel` is all it takes; `Tracing.create`
 * finds it by `ServiceLoader`. That replaced the `Class.forName` reflection core
 * used to load this module (#1133).
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates it through a public no-arg constructor.
 */
final class OpenTelemetryTracingBackend extends TracingBackend:
  val mode: TracingMode = TracingMode.OpenTelemetry

  /**
   * Builds the tracer from `settings.openTelemetry`. An SDK that cannot start is
   * reported as an error here, rather than as a tracer whose every call fails.
   * An unreachable collector is not such a failure: OTLP export is asynchronous,
   * so it surfaces only in the exporter's own logs.
   */
  def create(settings: TracingSettings): Result[Tracing] =
    val tracing = OpenTelemetryTracing.from(settings.openTelemetry)
    tracing.initializationFailure match
      case Some(error) => Left(UnknownError(s"OpenTelemetry tracing failed to initialise: $error", error))
      case None        => Right(tracing)
