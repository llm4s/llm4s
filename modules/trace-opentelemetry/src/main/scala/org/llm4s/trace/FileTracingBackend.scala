package org.llm4s.trace

import org.llm4s.llmconnect.config.{ FileTracingConfig, TracingSettings }
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

/**
 * Registers the file mode for `TracingMode.Named("file")` (`TRACING_MODE=file`, #2948): every trace written to a
 * local file as OTLP JSON Lines by [[OpenTelemetryTracing.toFiles]], with no tracing service.
 *
 * Declared in this module's `META-INF/services/org.llm4s.trace.spi.TracingBackend` beside
 * [[OpenTelemetryTracingBackend]]. A `class` rather than an `object` because `java.util.ServiceLoader` instantiates it
 * through a public no-arg constructor.
 */
final class FileTracingBackend extends TracingBackend:
  val mode: TracingMode = FileTracingConfig.Mode

  /**
   * Builds the tracing from `settings.extras`, the `llm4s.tracing.file` block this module's `reference.conf` binds to
   * `LLM4S_TRACE_DIR`, `OTEL_SERVICE_NAME` and `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`. A directory that
   * cannot be created, is not a directory or is not writable is a `Left`.
   */
  def create(settings: TracingSettings): Result[Tracing] =
    FileTracingConfig.fromExtras(settings.extras).flatMap(OpenTelemetryTracing.toFiles)
