package org.llm4s.config

import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.TracingMode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.tracing under the standard test configuration.
 */
class Llm4sConfigTracingSpec extends AnyWordSpec with Matchers {

  "Llm4sConfig.tracing" should {
    "load tracing settings from llm4s.* defaults" in {
      val pure: TracingSettings =
        Llm4sConfig.tracing().fold(err => fail(err.toString), identity)

      pure.mode shouldBe TracingMode.Console
      // Console's own block, off by default (#2311); core binds no backend's keys (#1133).
      pure.extras shouldBe Map("captureContent" -> "false")
    }

    "load them from a given source, as the Spring Boot starter's properties are (#2279)" in {
      val source =
        pureconfig.ConfigSource.string("""llm4s.tracing { mode = shop, shop { endpoint = "http://c:4317" } }""")
      val settings = Llm4sConfig.tracing(source).fold(err => fail(err.toString), identity)
      settings.mode shouldBe TracingMode.Named("shop")
      settings.extras shouldBe Map("endpoint" -> "http://c:4317")
    }
  }
}
