package org.llm4s.llmconnect.config

import com.typesafe.config.ConfigUtil
import org.llm4s.trace.TracingMode

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Connection settings for an OpenTelemetry collector.
 *
 * Spans are exported via OTLP/gRPC to `endpoint`. Read from the
 * `llm4s.tracing.opentelemetry` block, which this module's `reference.conf` binds to
 * `OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_ENDPOINT`; with
 * `TRACING_MODE=opentelemetry` (or `otel`) that block reaches the backend as
 * `TracingSettings.extras` ([[OpenTelemetryConfig.fromExtras]]).
 *
 * This lived in `llm4s-core` until slice 6 (#1133), as `TracingSettings.openTelemetry`.
 * The package is unchanged.
 *
 * @param serviceName logical service name attached to every span as `service.name`;
 *                    defaults to `"llm4s-agent"`
 * @param endpoint    OTLP/gRPC collector address; defaults to `"http://localhost:4317"`
 * @param headers     additional HTTP headers sent with each OTLP export request
 *                    (e.g. authentication tokens for hosted collectors)
 */
case class OpenTelemetryConfig(
  serviceName: String = OpenTelemetryConfig.DEFAULT_SERVICE_NAME,
  endpoint: String = OpenTelemetryConfig.DEFAULT_ENDPOINT,
  headers: Map[String, String] = Map.empty
)

object OpenTelemetryConfig {

  /** The mode OpenTelemetry is registered under: `TRACING_MODE=opentelemetry` (or `otel`). */
  val Mode: TracingMode = TracingMode.Named("opentelemetry")

  val DEFAULT_SERVICE_NAME = "llm4s-agent"

  val DEFAULT_ENDPOINT = "http://localhost:4317"

  /**
   * Builds the config from the `llm4s.tracing.opentelemetry` block, as
   * `TracingSettings.extras` carries it: `serviceName`, `endpoint`, and one
   * `headers.<name>` entry per header. Unset values take the defaults.
   */
  def fromExtras(extras: Map[String, String]): OpenTelemetryConfig = {
    // An extras key is a HOCON path, quoted where a header name needs it
    // (`headers."X.Team"`), so it is split as one rather than on dots.
    val headers = extras.flatMap { case (key, value) =>
      Try(ConfigUtil.splitPath(key).asScala.toList).toOption match {
        case Some("headers" :: name :: Nil) => Some(name -> value)
        case _                              => None
      }
    }
    OpenTelemetryConfig(
      serviceName = extras.get("serviceName").getOrElse(DEFAULT_SERVICE_NAME),
      endpoint = extras.get("endpoint").getOrElse(DEFAULT_ENDPOINT),
      headers = headers
    )
  }
}
