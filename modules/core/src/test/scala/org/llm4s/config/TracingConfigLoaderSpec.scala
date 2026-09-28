package org.llm4s.config

import org.llm4s.trace.TracingMode
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * Unit tests for TracingConfigLoader: the mode, and the selected mode's block as `extras`.
 *
 * These tests use ConfigSource.string() to provide deterministic HOCON input
 * without relying on environment variables or external configuration files.
 *
 * Core reads no backend's keys since slice 6 (#1133). The Langfuse field tests moved to
 * `llm4s-observability` (`LangfuseTracingConfigSpec`) and the OpenTelemetry header tests to
 * `llm4s-observability-otel` (`OpenTelemetryTracingConfigSpec`), each with the
 * `reference.conf` block it proves.
 */
class TracingConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  private def load(hocon: String) = TracingConfigLoader.load(ConfigSource.string(hocon))

  "TracingConfigLoader" should {

    "use console when the tracing section is missing" in {
      val settings = load("llm4s {}").value
      settings.mode shouldBe TracingMode.Console
      settings.extras shouldBe empty
    }

    "use console when mode is not specified" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    langfuse {
          |      publicKey = "pk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val settings = load(hocon).value
      settings.mode shouldBe TracingMode.Console // Default
      // The langfuse block belongs to a mode that is not selected, so it is not read.
      settings.extras shouldBe empty
    }
  }

  // --------------------------------------------------------------------------
  // Tracing Mode Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader mode parsing" should {

    "parse 'console' mode correctly" in {
      load("llm4s { tracing { mode = \"console\" } }").value.mode shouldBe TracingMode.Console
    }

    "parse 'langfuse' as Named, for llm4s-observability's backend to claim" in {
      load("llm4s { tracing { mode = \"langfuse\" } }").value.mode shouldBe TracingMode.Named("langfuse")
    }

    "parse 'opentelemetry' and 'otel' as Named(opentelemetry)" in {
      load("llm4s { tracing { mode = \"opentelemetry\" } }").value.mode shouldBe TracingMode.Named("opentelemetry")
      load("llm4s { tracing { mode = \"otel\" } }").value.mode shouldBe TracingMode.Named("opentelemetry")
    }

    "parse 'noop' mode correctly" in {
      load("llm4s { tracing { mode = \"noop\" } }").value.mode shouldBe TracingMode.NoOp
    }

    "handle mode with mixed case" in {
      load("llm4s { tracing { mode = \"LANGFUSE\" } }").value.mode shouldBe TracingMode.Named("langfuse")
    }

    "read a mode core has no case for as Named, for a backend outside core (D2, #1133)" in {
      val result = load("llm4s { tracing { mode = \"Unknown-Mode\" } }")

      result.value.mode shouldBe TracingMode.Named("unknown-mode")
      // With no backend registered for it, building the tracer still degrades to NoOp.
      org.llm4s.trace.Tracing.create(result.value) shouldBe a[org.llm4s.trace.NoOpTracing]
    }

    "handle empty mode string by using default" in {
      load("llm4s { tracing { mode = \"  \" } }").value.mode shouldBe TracingMode.Console
    }
  }

  // --------------------------------------------------------------------------
  // Malformed Configuration Tests
  // --------------------------------------------------------------------------

  "TracingConfigLoader with malformed config" should {

    "fail gracefully when llm4s root is missing" in {
      val hocon =
        """
          |someOtherConfig {
          |  value = "test"
          |}
          |""".stripMargin

      load(hocon).left.value.message should include("Failed to load llm4s tracing config via PureConfig")
    }

    "fail gracefully when tracing section has wrong structure" in {
      load("llm4s { tracing = \"invalid-scalar-value\" }").left.value.message should include(
        "Failed to load llm4s tracing config via PureConfig"
      )
    }

    "reject the selected mode's block when it is not an object, naming the path" in {
      val hocon =
        """
          |llm4s {
          |  tracing {
          |    mode = "langfuse"
          |    langfuse = "should-be-an-object"
          |  }
          |}
          |""".stripMargin

      val error = load(hocon).left.value
      error shouldBe a[org.llm4s.error.ConfigurationError]
      error.message should include("llm4s.tracing.langfuse")
      error.message should include("must be an object, but is a string")
    }

    "ignore a malformed block for a mode that is not selected" in {
      val hocon = "llm4s.tracing { mode = \"datadog\", datadog.site = \"eu\", langfuse = \"oops\" }"
      load(hocon).value.extras shouldBe Map("site" -> "eu")
    }
  }

  // --------------------------------------------------------------------------
  // A backend's own block: TracingSettings.extras
  // --------------------------------------------------------------------------

  "TracingConfigLoader extras" should {

    "carry the selected mode's block, flattened to strings" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "datadog"
          |  datadog {
          |    site     = "datadoghq.eu"
          |    apiKey   = "dd-secret"
          |    sampling = 0.5
          |    tags { team = "ml" }
          |    hosts    = ["a", "b"]
          |  }
          |}
          |""".stripMargin

      val settings = load(hocon).value

      settings.mode shouldBe TracingMode.Named("datadog")
      settings.extras shouldBe Map(
        "site"      -> "datadoghq.eu",
        "apiKey"    -> "dd-secret",
        "sampling"  -> "0.5",
        "tags.team" -> "ml",
        "hosts"     -> """["a","b"]"""
      )
    }

    "carry the canonical block for an alias: otel reads llm4s.tracing.opentelemetry" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "otel"
          |  opentelemetry { endpoint = "http://collector:4317" }
          |}
          |""".stripMargin

      load(hocon).value.extras shouldBe Map("endpoint" -> "http://collector:4317")
    }

    "read only the selected mode's block" in {
      val hocon =
        """
          |llm4s.tracing {
          |  mode = "datadog"
          |  datadog { site = "datadoghq.eu" }
          |  honeycomb { dataset = "other" }
          |}
          |""".stripMargin

      load(hocon).value.extras shouldBe Map("site" -> "datadoghq.eu")
    }

    "be empty when the selected mode has no block" in {
      load("llm4s.tracing.mode = \"datadog\"").value.extras shouldBe empty
      load("llm4s.tracing { mode = \"datadog\", datadog = null }").value.extras shouldBe empty
      load("llm4s {}").value.extras shouldBe empty
    }

    "fail, naming the path, when the selected mode's key is present but not an object" in {
      // Codex review on #1237: treated as absent, this started the backend on its
      // defaults and silently ignored the operator's endpoint.
      val scalar = load("llm4s.tracing { mode = \"opentelemetry\", opentelemetry = \"http://collector:4317\" }")
      val list   = load("llm4s.tracing { mode = \"datadog\", datadog = [\"a\"] }")

      scalar.left.value.message should include("llm4s.tracing.opentelemetry must be an object, but is a string")
      // The misplaced value is not echoed: it may be a secret.
      (scalar.left.value.message should not).include("collector:4317")
      list.left.value.message should include("llm4s.tracing.datadog must be an object, but is a list")
    }

    "check the canonical block when the mode is an alias" in {
      // The alias resolves to the canonical block, and that block is checked too.
      load("llm4s.tracing { mode = \"otel\", opentelemetry = 4317 }").left.value.message should include(
        "llm4s.tracing.opentelemetry must be an object, but is a number"
      )
    }

    "redact the extras values in toString" in {
      val hocon = "llm4s.tracing { mode = \"datadog\", datadog.apiKey = \"dd-secret\" }"
      val shown = load(hocon).value.toString

      shown should include("apiKey -> ***")
      (shown should not).include("dd-secret")
    }
  }
}
