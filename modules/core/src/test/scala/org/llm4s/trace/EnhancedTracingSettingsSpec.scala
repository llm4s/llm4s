package org.llm4s.trace

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EnhancedTracingSettingsSpec extends AnyWordSpec with Matchers {
  "EnhancedTracing.createFromEnv" should {
    "return Console tracing by default" in {
      val res = EnhancedTracing.createFromEnv()
      res.fold(err => fail(err.toString), tracer => tracer shouldBe a[EnhancedConsoleTracing])
    }
  }
}
