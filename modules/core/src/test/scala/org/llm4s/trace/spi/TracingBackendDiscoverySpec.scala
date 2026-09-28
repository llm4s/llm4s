package org.llm4s.trace.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.spi.fixtures.{ FixtureTracing, FixtureTracingBackend, OtherFixtureTracingBackend }
import org.llm4s.trace.{ ConsoleTracing, NoOpTracing, Tracing, TracingMode }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLClassLoader

/**
 * Covers `META-INF/services` discovery of tracing backends (#1133) - the point
 * at which adding a tracing backend stops being an edit to `Tracing.create` and
 * becomes adding a dependency, as slice 4 did for providers.
 *
 * Each case builds a class loader over one fixture directory under
 * `src/test/resources/tracing-discovery`. They live in subdirectories rather than
 * at the resource root so that no fixture backend is on every test's classpath.
 * The loaders delegate to the test class loader, which holds no backend at all:
 * core registers none since Langfuse moved to `llm4s-observability` (#1133). Each
 * backend module proves its own registration (`LangfuseTracingBackendSpec`,
 * `OpenTelemetryTracingBackendSpec`).
 */
class TracingBackendDiscoverySpec extends AnyWordSpec with Matchers with EitherValues:

  private def loaderFor(directory: String): ClassLoader =
    val parent = getClass.getClassLoader
    val url = Option(parent.getResource(s"tracing-discovery/$directory/"))
      .getOrElse(fail(s"fixture directory 'tracing-discovery/$directory/' is missing from test resources"))
    new URLClassLoader(Array(url), parent)

  private def settings(mode: TracingMode): TracingSettings =
    TracingSettings(mode = mode)

  "TracingBackends.discover" should {

    "find no backend in core, which registers none since Langfuse left it (#1133)" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.discovered shouldBe true
      backends.modes shouldBe empty
      backends.failures shouldBe empty
    }

    "not find a Langfuse or OpenTelemetry backend in core, which depends on neither module" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)

      backends.find(TracingMode.Named("langfuse")) shouldBe None
      backends.find(TracingMode.Named("opentelemetry")) shouldBe None
    }

    "find a backend that llm4s-core knows nothing about, under a Named mode" in {
      val backends = TracingBackends.discover(loaderFor("good"))

      backends.find(TracingMode.Named("fixture")).map(_.getClass) shouldBe Some(classOf[FixtureTracingBackend])
    }

    "match a Named mode case-insensitively" in {
      TracingBackends.discover(loaderFor("good")).find(TracingMode.Named("FIXTURE")) should not be empty
    }

    "record an unloadable service entry instead of throwing, and keep the working one" in {
      val backends = TracingBackends.discover(loaderFor("broken"))

      backends.failures.mkString should include("NoSuchTracingBackend")
      backends.find(TracingMode.Named("fixture")) should not be empty
    }

    "survive a backend that throws a LinkageError, and keep the working one" in {
      // `Try` does not catch LinkageError; the guard must, or one stale jar takes out the scan.
      val backends = TracingBackends.discover(loaderFor("linkage"))

      backends.failures.mkString should include("AbstractMethodError")
      backends.find(TracingMode.Named("fixture")) should not be empty
    }

    "let the later of two backends for one mode win" in {
      val backends = TracingBackends.discover(loaderFor("duplicate"))

      backends.find(TracingMode.Named("fixture")).map(_.getClass) shouldBe Some(classOf[OtherFixtureTracingBackend])
    }
  }

  "TracingBackends.of / withBackend" should {

    "register a backend explicitly, for a shaded jar whose services files did not survive" in {
      val backends = TracingBackends.of(new FixtureTracingBackend)

      backends.discovered shouldBe false
      backends.find(TracingMode.Named("fixture")) should not be empty
      backends.find(TracingMode.Named("langfuse")) shouldBe None
    }

    "let an explicit backend replace a discovered one for the same mode" in {
      val backends = TracingBackends.discover(loaderFor("good")).withBackend(new OtherFixtureTracingBackend)

      backends.find(TracingMode.Named("fixture")).map(_.getClass) shouldBe Some(classOf[OtherFixtureTracingBackend])
    }
  }

  "Tracing.fromSettings" should {

    "build NoOp and Console itself, needing no backend at all" in {
      Tracing.fromSettings(settings(TracingMode.NoOp), TracingBackends.empty).value shouldBe a[NoOpTracing]
      Tracing.fromSettings(settings(TracingMode.Console), TracingBackends.empty).value shouldBe a[ConsoleTracing]
    }

    "treat a hand-built Named mode with a built-in name as that built-in" in {
      Tracing
        .fromSettings(settings(TracingMode.Named("Console")), TracingBackends.empty)
        .value shouldBe a[ConsoleTracing]
      Tracing.fromSettings(settings(TracingMode.Named("NOOP")), TracingBackends.empty).value shouldBe a[NoOpTracing]
    }

    "dispatch a Named mode to the backend registered for it, passing the settings through" in {
      val s       = settings(TracingMode.Named("fixture"))
      val tracing = Tracing.fromSettings(s, TracingBackends.discover(loaderFor("good"))).value

      tracing shouldBe a[FixtureTracing]
      tracing.asInstanceOf[FixtureTracing].settings shouldBe s
    }

    "fail with a ConfigurationError naming the artifact when OpenTelemetry has no backend" in {
      val error = Tracing.fromSettings(settings(TracingMode.fromString("otel"))).left.value

      error shouldBe a[ConfigurationError]
      error.message should include("opentelemetry")
      error.message should include("llm4s-observability-otel")
    }

    "fail with a ConfigurationError naming the artifact when Langfuse has no backend" in {
      val error = Tracing.fromSettings(settings(TracingMode.fromString("langfuse"))).left.value

      error shouldBe a[ConfigurationError]
      error.message should include("'langfuse'")
      error.message should include("'llm4s-observability'")
    }

    "fail with a ConfigurationError listing what is registered when a Named mode has no backend" in {
      val error = Tracing.fromSettings(settings(TracingMode.Named("datadog"))).left.value

      error shouldBe a[ConfigurationError]
      error.message should include("datadog")
      error.message should include("Available modes: console, noop")
      (error.message should not).include("Add the")
    }

    "turn a backend that throws into an error rather than an exception" in {
      val backends = TracingBackends.discover(loaderFor("throwing"))
      val error    = Tracing.fromSettings(settings(TracingMode.Named("throwing")), backends).left.value

      error.message should include("this backend is broken")
    }
  }

  "Tracing.create" should {

    "fall back to NoOp when the configured backend is missing" in {
      Tracing.create(settings(TracingMode.Named("opentelemetry"))) shouldBe a[NoOpTracing]
      Tracing.create(settings(TracingMode.Named("langfuse"))) shouldBe a[NoOpTracing]
      Tracing.create(settings(TracingMode.Named("datadog"))) shouldBe a[NoOpTracing]
    }
  }
