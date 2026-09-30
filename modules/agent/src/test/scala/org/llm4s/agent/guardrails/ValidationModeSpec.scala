package org.llm4s.agent.guardrails

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidationModeSpec extends AnyFlatSpec with Matchers {

  "ValidationMode" should "have All, Any, and First variants" in {
    ValidationMode.All shouldBe a[ValidationMode]
    ValidationMode.Any shouldBe a[ValidationMode]
    ValidationMode.First shouldBe a[ValidationMode]
  }

  it should "have distinct variants" in {
    ValidationMode.All should not be ValidationMode.Any
    ValidationMode.All should not be ValidationMode.First
    ValidationMode.Any should not be ValidationMode.First
  }
}
