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

  // ==========================================================================
  // GuardrailResult variants
  // ==========================================================================

  "GuardrailResult.Passed" should "wrap a value" in {
    val result = GuardrailResult.Passed("hello")
    result.value shouldBe "hello"
  }

  "GuardrailResult.Fixed" should "carry original, fixed, and violations" in {
    val result = GuardrailResult.Fixed("bad input", "fixed input", Seq("removed profanity"))
    result.original shouldBe "bad input"
    result.fixed shouldBe "fixed input"
    result.violations shouldBe Seq("removed profanity")
  }

  "GuardrailResult.Warned" should "carry value and violations" in {
    val result = GuardrailResult.Warned("text", Seq("too long"))
    result.value shouldBe "text"
    result.violations shouldBe Seq("too long")
  }

  "GuardrailResult.Blocked" should "carry violations" in {
    val result = GuardrailResult.Blocked(Seq("injection detected", "malicious content"))
    result.violations should have size 2
  }

  // ==========================================================================
  // GuardrailResultOps
  // ==========================================================================

  "GuardrailResultOps.toOption" should "return Some for Passed" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Passed("value")
    result.toOption shouldBe Some("value")
  }

  it should "return Some(fixed) for Fixed" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Fixed("orig", "fixed", Seq("v"))
    result.toOption shouldBe Some("fixed")
  }

  it should "return Some(value) for Warned" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Warned("value", Seq("w"))
    result.toOption shouldBe Some("value")
  }

  it should "return None for Blocked" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Blocked(Seq("blocked"))
    result.toOption shouldBe None
  }

  "GuardrailResultOps.getOrElse" should "return value for Passed" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Passed("value")
    result.getOrElse("default") shouldBe "value"
  }

  it should "return default for Blocked" in {
    import GuardrailResult._
    val result: GuardrailResult[String] = Blocked(Seq("blocked"))
    result.getOrElse("default") shouldBe "default"
  }

  "GuardrailResultOps.isSuccess" should "return true for Passed" in {
    import GuardrailResult._
    Passed("v").isSuccess shouldBe true
  }

  it should "return true for Fixed" in {
    import GuardrailResult._
    Fixed("o", "f", Seq.empty).isSuccess shouldBe true
  }

  it should "return true for Warned" in {
    import GuardrailResult._
    Warned("v", Seq("w")).isSuccess shouldBe true
  }

  it should "return false for Blocked" in {
    import GuardrailResult._
    Blocked(Seq("b")).isSuccess shouldBe false
  }

  "GuardrailResultOps.isBlocked" should "return true only for Blocked" in {
    import GuardrailResult._
    Passed("v").isBlocked shouldBe false
    Fixed("o", "f", Seq.empty).isBlocked shouldBe false
    Warned("v", Seq("w")).isBlocked shouldBe false
    Blocked(Seq("b")).isBlocked shouldBe true
  }

  "GuardrailResultOps.hasWarnings" should "return true for Warned" in {
    import GuardrailResult._
    Warned("v", Seq("warning")).hasWarnings shouldBe true
  }

  it should "return true for Fixed with violations" in {
    import GuardrailResult._
    Fixed("o", "f", Seq("fixed something")).hasWarnings shouldBe true
  }

  it should "return false for Fixed with empty violations" in {
    import GuardrailResult._
    Fixed("o", "f", Seq.empty).hasWarnings shouldBe false
  }

  it should "return false for Passed" in {
    import GuardrailResult._
    Passed("v").hasWarnings shouldBe false
  }

  it should "return false for Blocked" in {
    import GuardrailResult._
    Blocked(Seq("b")).hasWarnings shouldBe false
  }
}
