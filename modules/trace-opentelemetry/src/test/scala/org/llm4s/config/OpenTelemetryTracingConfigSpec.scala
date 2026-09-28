package org.llm4s.config

import org.llm4s.llmconnect.config.OpenTelemetryConfig
import org.llm4s.testutil.ReferenceConfig
import org.llm4s.trace.TracingMode
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The `llm4s.tracing.opentelemetry` block and its `OTEL_*` bindings, which moved from
 * core's `reference.conf` to this module's with the code that reads them (#1133). The
 * header cases were in core's `TracingConfigLoaderSpec`, which read them as
 * `TracingSettings.openTelemetry`.
 */
class OpenTelemetryTracingConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  private def otel(hocon: String, env: Map[String, String]): OpenTelemetryConfig = {
    val settings = TracingConfigLoader.load(ReferenceConfig.withEnv(hocon, env)).value
    settings.mode shouldBe TracingMode.Named("opentelemetry")
    OpenTelemetryConfig.fromExtras(settings.extras)
  }

  "llm4s-observability-otel's reference.conf" should {

    "default the service name and endpoint" in {
      otel("", Map("TRACING_MODE" -> "opentelemetry")) shouldBe OpenTelemetryConfig(
        "llm4s-agent",
        "http://localhost:4317"
      )
    }

    "bind OTEL_SERVICE_NAME and OTEL_EXPORTER_OTLP_ENDPOINT, for the otel alias too" in {
      val env = Map(
        "TRACING_MODE"                -> "otel",
        "OTEL_SERVICE_NAME"           -> "svc-from-env",
        "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://collector:4317"
      )

      otel("", env) shouldBe OpenTelemetryConfig("svc-from-env", "http://collector:4317")
    }

    "load the headers map from llm4s.tracing.opentelemetry.headers" in {
      val hocon =
        """
          |llm4s.tracing.opentelemetry.headers {
          |  Authorization = ${?OTEL_AUTH_HEADER}
          |  X-Team = "search"
          |  "X.Dotted" = "kept whole"
          |}
          |""".stripMargin

      otel(hocon, Map("TRACING_MODE" -> "opentelemetry", "OTEL_AUTH_HEADER" -> "Bearer t")).headers shouldBe Map(
        "Authorization" -> "Bearer t",
        "X-Team"        -> "search",
        "X.Dotted"      -> "kept whole"
      )
    }

    "not read OTEL_EXPORTER_OTLP_HEADERS, as reference.conf says" in {
      // reference.conf binds the service name and endpoint to the standard OTel variables,
      // but not the headers one: a comma-separated string cannot fill a map.
      val env = Map(
        "TRACING_MODE"                -> "opentelemetry",
        "OTEL_SERVICE_NAME"           -> "svc-from-env",
        "OTEL_EXPORTER_OTLP_ENDPOINT" -> "http://collector:4317",
        "OTEL_EXPORTER_OTLP_HEADERS"  -> "Authorization=Bearer t"
      )

      val config = otel("", env)
      config.serviceName shouldBe "svc-from-env"
      config.endpoint shouldBe "http://collector:4317"
      config.headers shouldBe empty
    }
  }
}
