package org.llm4s.llmconnect.config

import org.llm4s.trace.TracingMode

/**
 * Tracing configuration used by [[org.llm4s.trace.Tracing]].
 *
 * `mode` selects the backend. Core builds `NoOp` and `Console` itself and needs
 * nothing more; every other mode is served by a [[org.llm4s.trace.spi.TracingBackend]]
 * on the classpath, which reads its own keys from `extras`: the block
 * `llm4s.tracing.<mode>` for the selected mode, flattened to strings. The backend's
 * module ships that block's defaults and `${?VAR}` bindings in its own
 * `reference.conf` - the arrangement provider descriptors have with
 * `NamedProviderConfig.extras`. So `llm4s-observability` reads
 * `llm4s.tracing.langfuse` (`LangfuseConfig.fromExtras`) and
 * `llm4s-observability-otel` reads `llm4s.tracing.opentelemetry`.
 *
 * Until slice 6 (#1133) this class also carried typed `langfuse` and
 * `openTelemetry` fields; they left core with their backends.
 *
 * `toString` redacts the `extras` values, which may be credentials.
 *
 * @param mode   selects the tracing backend: `Console`, `NoOp`, or `Named` for a backend
 *               outside core (`Named("langfuse")`, `Named("opentelemetry")`, ...)
 * @param extras every value under `llm4s.tracing.<mode>` for the selected mode, as a string,
 *               keyed by its path within that block (`publicKey`, `headers.Authorization`); a
 *               list or object value is rendered as HOCON. Empty when the block is absent.
 */
case class TracingSettings(
  mode: TracingMode,
  extras: Map[String, String] = Map.empty
) {
  override def toString: String =
    s"TracingSettings($mode,${extras.keys.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"
}
