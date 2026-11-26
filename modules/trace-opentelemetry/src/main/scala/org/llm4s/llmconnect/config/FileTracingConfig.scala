package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result

import java.nio.file.{ Path, Paths }
import scala.util.Try

/**
 * Settings for the file tracing mode (`TRACING_MODE=file`, #2948): every trace written to a local file as OTLP JSON
 * Lines, with no tracing service.
 *
 * Read from the `llm4s.tracing.file` block, which this module's `reference.conf` binds to `LLM4S_TRACE_DIR`,
 * `OTEL_SERVICE_NAME` and `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`; with `TRACING_MODE=file` that block
 * reaches the backend as `TracingSettings.extras` ([[FileTracingConfig.fromExtras]]). In code:
 *
 * {{{
 * OpenTelemetryTracing.toFiles(FileTracingConfig(Paths.get("traces")).withCaptureContent(true))
 * }}}
 *
 * @param dir            the trace directory, created on demand: each placed trace is appended to
 *                       `<dir>/<trace-id>.jsonl`, every unplaced event to one `<dir>/unplaced-<start>-<id>.jsonl` per
 *                       tracing instance; `./llm4s-traces` by default
 * @param serviceName    `service.name` on every span's resource; `"llm4s-agent"` by default
 * @param captureContent record message content - prompts, completions, an agent's query, tool input and output -
 *                       redacted, as `gen_ai.input.messages` / `gen_ai.output.messages`. Off by default, since the
 *                       files sit on disk in plain text; turn it on to see what a run actually said
 */
final case class FileTracingConfig private (
  dir: Path,
  serviceName: String,
  captureContent: Boolean
) {

  /** Sets the trace directory. */
  def withDir(dir: Path): FileTracingConfig = copy(dir = dir)

  /** Sets `service.name` on every span's resource. */
  def withServiceName(serviceName: String): FileTracingConfig = copy(serviceName = serviceName)

  /** Records message content, redacted, on the spans (off by default). */
  def withCaptureContent(captureContent: Boolean): FileTracingConfig = copy(captureContent = captureContent)
}

object FileTracingConfig {

  /** The mode the file backend is registered under: `TRACING_MODE=file`. */
  val Mode: TracingMode = TracingMode.Named("file")

  /** `./llm4s-traces`, relative to the working directory. */
  val DefaultDir: Path = Paths.get("llm4s-traces")

  val DefaultServiceName: String = OpenTelemetryConfig.DEFAULT_SERVICE_NAME

  /** Creates the settings; every argument has its default, so `FileTracingConfig()` writes to `./llm4s-traces`. */
  def apply(
    dir: Path = DefaultDir,
    serviceName: String = DefaultServiceName,
    captureContent: Boolean = false
  ): FileTracingConfig =
    new FileTracingConfig(dir, serviceName, captureContent)

  /**
   * Builds the settings from the `llm4s.tracing.file` block, as `TracingSettings.extras` carries it: `dir`,
   * `serviceName` and `captureContent` (`true` turns it on). Unset or blank values take the defaults; a `dir` that is
   * not a valid path on this platform is a [[org.llm4s.error.ConfigurationError]].
   */
  def fromExtras(extras: Map[String, String]): Result[FileTracingConfig] = {
    def value(key: String) = extras.get(key).map(_.trim).filter(_.nonEmpty)
    val dir = value("dir") match {
      case None => Right(DefaultDir)
      case Some(raw) =>
        Try(Paths.get(raw)).toEither.left.map(e =>
          ConfigurationError(s"llm4s.tracing.file.dir is not a valid path: '$raw' (${e.getMessage})")
        )
    }
    dir.map { d =>
      FileTracingConfig(
        dir = d,
        serviceName = value("serviceName").getOrElse(DefaultServiceName),
        captureContent = value("captureContent").exists(_.equalsIgnoreCase("true"))
      )
    }
  }
}
