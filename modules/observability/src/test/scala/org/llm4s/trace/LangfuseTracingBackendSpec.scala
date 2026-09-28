package org.llm4s.trace

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.spi.TracingBackends
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-observability` registers Langfuse itself, and what it registers works.
 *
 * Core carried this registration in a temporary services entry until the carve (#1133);
 * this is the proof that depending on the module is now enough - the counterpart of
 * `OpenTelemetryTracingBackendSpec` and the provider modules' `Llm4s<Name>ModuleSpec`.
 * Nothing here sends a batch: the keys are fixtures and no event is traced.
 */
class LangfuseTracingBackendSpec extends AnyWordSpec with Matchers with EitherValues:

  private val keys = Map("publicKey" -> "pk-lf-test", "secretKey" -> "sk-lf-test")

  private def settings(extras: Map[String, String]): TracingSettings =
    TracingSettings(mode = TracingMode.fromString("langfuse"), extras = extras)

  "the llm4s-observability services entry" should {

    "be discovered for the langfuse mode" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.find(TracingMode.Named("langfuse")).map(_.getClass) shouldBe Some(classOf[LangfuseTracingBackend])
      backends.failures shouldBe empty
    }

    "be the only backend this module registers, under the mode LangfuseConfig names" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.modes.map(_.name) shouldBe Vector("langfuse")
      new LangfuseTracingBackend().mode shouldBe LangfuseConfig.Mode
    }

    "be registrable explicitly, for a shaded jar whose services files did not survive" in {
      Tracing
        .fromSettings(settings(keys), TracingBackends.of(new LangfuseTracingBackend))
        .value shouldBe a[LangfuseTracing]
    }
  }

  "Tracing.fromSettings and Tracing.create" should {

    "build LangfuseTracing for TRACING_MODE=langfuse, whatever its case" in {
      Tracing.fromSettings(settings(keys)).value shouldBe a[LangfuseTracing]
      Tracing.create(settings(keys)) shouldBe a[LangfuseTracing]
      Tracing
        .fromSettings(TracingSettings(TracingMode.Named("LangFuse"), keys))
        .value shouldBe a[LangfuseTracing]
    }

    "report missing keys by their config path and variable, rather than dropping every batch" in {
      val error = Tracing.fromSettings(settings(Map.empty)).left.value

      error shouldBe a[ConfigurationError]
      error.message shouldBe
        "Langfuse tracing is selected but llm4s.tracing.langfuse.publicKey (LANGFUSE_PUBLIC_KEY) and " +
        "llm4s.tracing.langfuse.secretKey (LANGFUSE_SECRET_KEY) are not set."
    }

    "name only the key that is missing, treating a blank one as missing" in {
      val error = Tracing.fromSettings(settings(Map("publicKey" -> "pk-lf-test", "secretKey" -> "  "))).left.value

      error.message shouldBe
        "Langfuse tracing is selected but llm4s.tracing.langfuse.secretKey (LANGFUSE_SECRET_KEY) is not set."
    }

    "fall back to NoOp in Tracing.create when a key is missing" in {
      Tracing.create(settings(Map("publicKey" -> "pk-lf-test"))) shouldBe a[NoOpTracing]
    }
  }
