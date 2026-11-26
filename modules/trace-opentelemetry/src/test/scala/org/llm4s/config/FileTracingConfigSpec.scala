package org.llm4s.config

import org.llm4s.llmconnect.config.FileTracingConfig
import org.llm4s.trace.TracingMode
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

/** The `llm4s.tracing.file` block this module's `reference.conf` ships, and its bindings (#2948). */
class FileTracingConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  private def file(hocon: String, env: Map[String, String]): FileTracingConfig = {
    val settings = TracingConfigLoader.load(ReferenceConfig.withEnv(hocon, env)).value
    settings.mode shouldBe TracingMode.Named("file")
    FileTracingConfig.fromExtras(settings.extras).value
  }

  "llm4s-observability-otel's reference.conf" should {

    "default the directory, the service name and content capture" in {
      file("", Map("TRACING_MODE" -> "file")) shouldBe FileTracingConfig(Paths.get("llm4s-traces"), "llm4s-agent")
      file("", Map("TRACING_MODE" -> "file")).captureContent shouldBe false
    }

    "bind LLM4S_TRACE_DIR, OTEL_SERVICE_NAME and OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT" in {
      val env = Map(
        "TRACING_MODE"                                       -> "file",
        "LLM4S_TRACE_DIR"                                    -> "/var/tmp/traces",
        "OTEL_SERVICE_NAME"                                  -> "svc",
        "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT" -> "true"
      )
      file("", env) shouldBe FileTracingConfig(Paths.get("/var/tmp/traces"), "svc", captureContent = true)
    }

    "turn content capture on with the one key" in {
      file("llm4s.tracing.file.captureContent = true", Map("TRACING_MODE" -> "file")).captureContent shouldBe true
    }
  }

  "FileTracingConfig.fromExtras" should {

    "take the defaults for blank values" in {
      FileTracingConfig.fromExtras(Map("dir" -> " ", "serviceName" -> "")).value shouldBe FileTracingConfig()
    }

    "refuse a dir that is not a path" in {
      FileTracingConfig.fromExtras(Map("dir" -> "bad\u0000path")).left.value.message should include(
        "llm4s.tracing.file.dir"
      )
    }
  }
}
