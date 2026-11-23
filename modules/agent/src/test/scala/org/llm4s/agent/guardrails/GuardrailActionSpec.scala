package org.llm4s.agent.guardrails

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuardrailActionSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // GuardrailAction values
  // ==========================================================================

  "GuardrailAction" should "have Block, Fix, and Warn variants" in {
    GuardrailAction.Block shouldBe a[GuardrailAction]
    GuardrailAction.Fix shouldBe a[GuardrailAction]
    GuardrailAction.Warn shouldBe a[GuardrailAction]
  }

  it should "have Block as default" in {
    GuardrailAction.default shouldBe GuardrailAction.Block
  }

}
