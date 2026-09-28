package org.llm4s.trace

import org.llm4s.config.Llm4sConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TracingSettingsSpec extends AnyWordSpec with Matchers {

  // Only the mode decides: LANGFUSE_PUBLIC_KEY is bound by llm4s-observability, not core.
  private def isTracingModeSet: Boolean =
    Option(System.getenv("TRACING_MODE")).exists(_.trim.nonEmpty) ||
      Option(System.getProperty("llm4s.tracing.mode")).exists(_.trim.nonEmpty)

  "Llm4sConfig.tracing + Tracing.create" should {
    "return Console tracing by default" in {
      // Skip test if a tracing mode is configured in the environment
      if (isTracingModeSet) {
        cancel("Test skipped: TRACING_MODE or llm4s.tracing.mode is set in the environment")
      }

      val res = Llm4sConfig.tracing().map(Tracing.create)
      res.fold(err => fail(err.toString), tracer => tracer shouldBe a[ConsoleTracing])
    }
  }
}
