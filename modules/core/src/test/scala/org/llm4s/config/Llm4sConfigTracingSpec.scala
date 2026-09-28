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
      // Console has no block of its own, and core binds no backend's keys (#1133).
      pure.extras shouldBe empty
    }
  }
}
