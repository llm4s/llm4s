package org.llm4s.llmconnect.config

import org.llm4s.config.DefaultConfig
import org.llm4s.trace.TracingMode
import org.llm4s.util.Redaction

/**
 * Connection and metadata settings for the Langfuse tracing backend.
 *
 * Normally obtained via [[org.llm4s.config.Llm4sConfig]] which reads
 * `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, and `LANGFUSE_BASE_URL`
 * from the environment.
 *
 * `toString` redacts both keys so that the config can be safely logged.
 *
 * @param url       Langfuse ingestion endpoint; defaults to `https://cloud.langfuse.com/api/public/ingestion`
 * @param publicKey Langfuse public API key; `None` disables tracing with a warning
 * @param secretKey Langfuse secret API key; redacted in `toString`
 * @param env       deployment environment tag attached to every trace; defaults to `"production"`
 * @param release   application release identifier forwarded to Langfuse; defaults to `"1.0.0"`
 * @param version   SDK/integration version forwarded to Langfuse; defaults to `"1.0.0"`
 */
case class LangfuseConfig(
  url: String = DefaultConfig.DEFAULT_LANGFUSE_URL,
  publicKey: Option[String] = None,
  secretKey: Option[String] = None,
  env: String = DefaultConfig.DEFAULT_LANGFUSE_ENV,
  release: String = DefaultConfig.DEFAULT_LANGFUSE_RELEASE,
  version: String = DefaultConfig.DEFAULT_LANGFUSE_VERSION
) {
  override def toString: String =
    s"LangfuseConfig(url=$url, publicKey=${Redaction.secretOpt(publicKey)}, secretKey=${Redaction.secretOpt(secretKey)}, " +
      s"env=$env, release=$release, version=$version)"
}

/**
 * Connection settings for an OpenTelemetry collector.
 *
 * Spans are exported via OTLP/gRPC to `endpoint`.  Set `OTEL_EXPORTER_OTLP_ENDPOINT`
 * in the environment (picked up by [[org.llm4s.config.Llm4sConfig]]) to override the
 * default local collector address.
 *
 * @param serviceName logical service name attached to every span as `service.name`;
 *                    defaults to `"llm4s-agent"`
 * @param endpoint    OTLP/gRPC collector address; defaults to `"http://localhost:4317"`
 * @param headers     additional HTTP headers sent with each OTLP export request
 *                    (e.g. authentication tokens for hosted collectors)
 */
case class OpenTelemetryConfig(
  serviceName: String = "llm4s-agent",
  endpoint: String = "http://localhost:4317",
  headers: Map[String, String] = Map.empty
)

/**
 * Combined tracing configuration used by [[org.llm4s.trace.Tracing]].
 *
 * `mode` selects the active backend; the other fields supply backend-specific
 * connection details.  Only the sub-config matching the active mode is used —
 * e.g. when `mode = TracingMode.Langfuse`, `openTelemetry` is ignored.
 *
 * @param mode          selects the tracing backend (`Langfuse`, `OpenTelemetry`, `Console`, or `NoOp`)
 * @param langfuse      Langfuse connection details; only used when `mode = TracingMode.Langfuse`
 * @param openTelemetry OpenTelemetry collector details; only used when `mode = TracingMode.OpenTelemetry`
 */
case class TracingSettings(
  mode: TracingMode,
  langfuse: LangfuseConfig,
  openTelemetry: OpenTelemetryConfig = OpenTelemetryConfig()
)
