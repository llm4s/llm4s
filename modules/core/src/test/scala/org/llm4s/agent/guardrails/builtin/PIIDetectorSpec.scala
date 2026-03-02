package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PIIDetectorSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Defaults
  // ==========================================================================

  "PIIDetector defaults" should "use Block as default mode" in {
    val detector = PIIDetector()
    detector.onFail shouldBe GuardrailAction.Block
  }

  it should "use PIIType.default as default types" in {
    val detector = PIIDetector()
    detector.piiTypes shouldBe PIIType.default
  }

  it should "have name PIIDetector" in {
    val detector = PIIDetector()
    detector.name shouldBe "PIIDetector"
  }

  // ==========================================================================
  // Description
  // ==========================================================================

  "PIIDetector description" should "list detected types" in {
    val detector = PIIDetector()
    detector.description shouldBe defined
    detector.description.get should include("SSN")
    detector.description.get should include("Email")
  }

  it should "reflect custom types" in {
    val detector = PIIDetector(piiTypes = Seq(PIIType.CreditCard))
    detector.description.get should include("Credit Card")
    (detector.description.get should not).include("SSN")
  }

  // ==========================================================================
  // Empty/whitespace input
  // ==========================================================================

  "PIIDetector" should "pass empty string" in {
    PIIDetector().validate("") shouldBe Right("")
  }

  it should "pass whitespace string" in {
    PIIDetector().validate("   \t\n  ") shouldBe Right("   \t\n  ")
  }

  // ==========================================================================
  // Block mode
  // ==========================================================================

  "PIIDetector in Block mode" should "return Left with ValidationError" in {
    val result = PIIDetector().validate("SSN: 123-45-6789")
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "include PII type names in error" in {
    val result = PIIDetector().validate("SSN: 123-45-6789")
    result.swap.toOption.get.message should include("SSN")
  }

  it should "include count per type in error" in {
    val result = PIIDetector().validate("a@b.com and c@d.com")
    result.swap.toOption.get.message should include("Email (2)")
  }

  // ==========================================================================
  // Fix mode
  // ==========================================================================

  "PIIDetector in Fix mode" should "mask and return Right" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    val result   = detector.validate("Email: test@example.com")
    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_EMAIL]")
  }

  it should "mask all configured types" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    val result   = detector.validate("SSN: 123-45-6789 email: a@b.com")
    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_SSN]")
    result.toOption.get should include("[REDACTED_EMAIL]")
  }

  it should "return original when clean" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    detector.validate("clean text") shouldBe Right("clean text")
  }

  // ==========================================================================
  // Warn mode
  // ==========================================================================

  "PIIDetector in Warn mode" should "return original text with PII" in {
    val detector = PIIDetector(onFail = GuardrailAction.Warn)
    detector.validate("SSN: 123-45-6789") shouldBe Right("SSN: 123-45-6789")
  }

  it should "return original when clean" in {
    val detector = PIIDetector(onFail = GuardrailAction.Warn)
    detector.validate("no pii") shouldBe Right("no pii")
  }

  // ==========================================================================
  // transform() method
  // ==========================================================================

  "PIIDetector.transform" should "mask in Fix mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    val result   = detector.transform("Email: test@example.com")
    result should include("[REDACTED_EMAIL]")
  }

  it should "passthrough in Block mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Block)
    detector.transform("SSN: 123-45-6789") shouldBe "SSN: 123-45-6789"
  }

  it should "passthrough in Warn mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Warn)
    detector.transform("SSN: 123-45-6789") shouldBe "SSN: 123-45-6789"
  }

  it should "passthrough when clean in Fix mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    detector.transform("clean text") shouldBe "clean text"
  }

  // ==========================================================================
  // Factory methods
  // ==========================================================================

  "PIIDetector.apply()" should "create with defaults" in {
    val d = PIIDetector()
    d.piiTypes shouldBe PIIType.default
    d.onFail shouldBe GuardrailAction.Block
  }

  "PIIDetector.apply(piiTypes)" should "accept custom types" in {
    val d = PIIDetector(piiTypes = Seq(PIIType.Email))
    d.piiTypes shouldBe Seq(PIIType.Email)
    d.onFail shouldBe GuardrailAction.Block
  }

  "PIIDetector.apply(onFail)" should "accept custom action" in {
    val d = PIIDetector(onFail = GuardrailAction.Fix)
    d.piiTypes shouldBe PIIType.default
    d.onFail shouldBe GuardrailAction.Fix
  }

  "PIIDetector.apply(piiTypes, onFail)" should "accept both" in {
    val d = PIIDetector(Seq(PIIType.SSN), GuardrailAction.Warn)
    d.piiTypes shouldBe Seq(PIIType.SSN)
    d.onFail shouldBe GuardrailAction.Warn
  }

  // ==========================================================================
  // Presets
  // ==========================================================================

  "PIIDetector.strict" should "use all types and Block" in {
    val d = PIIDetector.strict
    d.piiTypes shouldBe PIIType.all
    d.onFail shouldBe GuardrailAction.Block
  }

  "PIIDetector.masking" should "use default types and Fix" in {
    val d = PIIDetector.masking
    d.piiTypes shouldBe PIIType.default
    d.onFail shouldBe GuardrailAction.Fix
  }

  "PIIDetector.monitoring" should "use default types and Warn" in {
    val d = PIIDetector.monitoring
    d.piiTypes shouldBe PIIType.default
    d.onFail shouldBe GuardrailAction.Warn
  }

  "PIIDetector.financial" should "use CreditCard, SSN, BankAccount and Block" in {
    val d = PIIDetector.financial
    d.piiTypes should contain theSameElementsAs Seq(PIIType.CreditCard, PIIType.SSN, PIIType.BankAccount)
    d.onFail shouldBe GuardrailAction.Block
  }

  "PIIDetector.contactInfo" should "use Email, Phone and Fix" in {
    val d = PIIDetector.contactInfo
    d.piiTypes should contain theSameElementsAs Seq(PIIType.Email, PIIType.Phone)
    d.onFail shouldBe GuardrailAction.Fix
  }

  // ==========================================================================
  // Selective detection
  // ==========================================================================

  "PIIDetector with Email-only" should "ignore SSN" in {
    val d = PIIDetector(piiTypes = Seq(PIIType.Email))
    d.validate("SSN: 123-45-6789") shouldBe Right("SSN: 123-45-6789")
  }

  "PIIDetector with multiple types" should "detect all configured types" in {
    val d      = PIIDetector(piiTypes = Seq(PIIType.Email, PIIType.SSN))
    val result = d.validate("a@b.com and 123-45-6789")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Email")
    result.swap.toOption.get.message should include("SSN")
  }

  "PIIDetector.financial" should "block cards but not emails" in {
    val d = PIIDetector.financial
    d.validate("test@example.com") shouldBe Right("test@example.com")
    d.validate("Card: 4111-1111-1111-1111").isLeft shouldBe true
  }

  // ==========================================================================
  // Trait conformance
  // ==========================================================================

  "PIIDetector" should "be assignable to InputGuardrail" in {
    val guard: InputGuardrail = PIIDetector()
    guard.validate("clean") shouldBe Right("clean")
  }

  it should "be assignable to OutputGuardrail" in {
    val guard: OutputGuardrail = PIIDetector()
    guard.validate("clean") shouldBe Right("clean")
  }

  it should "transform via InputGuardrail reference" in {
    val guard: InputGuardrail = PIIDetector(onFail = GuardrailAction.Fix)
    guard.transform("test@example.com") should include("[REDACTED_EMAIL]")
  }

  it should "transform via OutputGuardrail reference" in {
    val guard: OutputGuardrail = PIIDetector(onFail = GuardrailAction.Fix)
    guard.transform("test@example.com") should include("[REDACTED_EMAIL]")
  }
}
