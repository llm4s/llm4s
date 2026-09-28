package org.llm4s.trace

import org.llm4s.config.LangfuseConfigKeys
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.spi.TracingBackend
import org.llm4s.types.Result

/**
 * Registers [[LangfuseTracing]] for `TracingMode.Named("langfuse")` (`TRACING_MODE=langfuse`).
 *
 * Declared in `llm4s-observability`'s `META-INF/services/org.llm4s.trace.spi.TracingBackend`,
 * so depending on the module is all it takes. It reads its settings from
 * `settings.extras` - the `llm4s.tracing.langfuse` block, which this module's
 * `reference.conf` binds to the `LANGFUSE_*` variables - as a provider descriptor
 * reads its named section.
 *
 * Without both keys it refuses to start, so `Tracing.fromSettings` reports the missing
 * variables and `Tracing.create` falls back to `NoOpTracing` with that error logged,
 * rather than returning a tracer that drops every batch.
 *
 * A `class` rather than an `object` because `java.util.ServiceLoader`
 * instantiates it through a public no-arg constructor.
 */
final class LangfuseTracingBackend extends TracingBackend:
  val mode: TracingMode = LangfuseConfig.Mode

  def create(settings: TracingSettings): Result[Tracing] =
    val config = LangfuseConfig.fromExtras(settings.extras)
    val missing = Seq(
      ("publicKey", LangfuseConfigKeys.LANGFUSE_PUBLIC_KEY, config.publicKey),
      ("secretKey", LangfuseConfigKeys.LANGFUSE_SECRET_KEY, config.secretKey)
    ).collect { case (key, env, None) => s"llm4s.tracing.langfuse.$key ($env)" }
    if missing.isEmpty then Right(LangfuseTracing.from(config))
    else
      Left(
        ConfigurationError(
          s"Langfuse tracing is selected but ${missing.mkString(" and ")} ${if missing.size == 1 then "is" else "are"} not set."
        )
      )
