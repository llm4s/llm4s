package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.{ PIIDetector, PIIMasker }
import org.llm4s.agent.guardrails.patterns.PIIPatterns
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PIIGuardrailSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // PIIPatterns Tests
  // ==========================================================================

  "PIIPatterns" should "detect SSN in various formats" in {
    val ssn1 = "My SSN is 123-45-6789"
    val ssn2 = "SSN: 123 45 6789"

    PIIPatterns.containsPII(ssn1, Seq(PIIType.SSN)) shouldBe true
    PIIPatterns.containsPII(ssn2, Seq(PIIType.SSN)) shouldBe true
  }

  it should "not detect invalid SSNs" in {
    // SSNs starting with 000, 666, or 9xx are invalid
    PIIPatterns.containsPII("000-12-3456", Seq(PIIType.SSN)) shouldBe false
    PIIPatterns.containsPII("666-12-3456", Seq(PIIType.SSN)) shouldBe false
    PIIPatterns.containsPII("900-12-3456", Seq(PIIType.SSN)) shouldBe false
  }

  it should "detect credit card numbers" in {
    // Visa (4xxx format, 16 digits in groups of 4)
    PIIPatterns.containsPII("Card: 4111111111111111", Seq(PIIType.CreditCard)) shouldBe true
    PIIPatterns.containsPII("Card: 4111-1111-1111-1111", Seq(PIIType.CreditCard)) shouldBe true
    // MasterCard (51-55xx format)
    PIIPatterns.containsPII("Card: 5500-0000-0000-0004", Seq(PIIType.CreditCard)) shouldBe true
    // Discover (6011xx format)
    PIIPatterns.containsPII("Card: 6011000000000004", Seq(PIIType.CreditCard)) shouldBe true
  }

  it should "detect email addresses" in {
    PIIPatterns.containsPII("Contact: test@example.com", Seq(PIIType.Email)) shouldBe true
    PIIPatterns.containsPII("Email: user.name+tag@domain.co.uk", Seq(PIIType.Email)) shouldBe true
    PIIPatterns.containsPII("No email here", Seq(PIIType.Email)) shouldBe false
  }

  it should "detect phone numbers" in {
    PIIPatterns.containsPII("Call me at (555) 123-4567", Seq(PIIType.Phone)) shouldBe true
    PIIPatterns.containsPII("Phone: 555-123-4567", Seq(PIIType.Phone)) shouldBe true
    PIIPatterns.containsPII("Tel: +1 555.123.4567", Seq(PIIType.Phone)) shouldBe true
  }

  it should "detect IP addresses" in {
    PIIPatterns.containsPII("Server at 192.168.1.1", Seq(PIIType.IPAddress)) shouldBe true
    PIIPatterns.containsPII("IP: 10.0.0.255", Seq(PIIType.IPAddress)) shouldBe true
    // Invalid IP (octet > 255) should not match
    PIIPatterns.containsPII("Invalid: 999.999.999.999", Seq(PIIType.IPAddress)) shouldBe false
  }

  it should "mask detected PII with placeholders" in {
    val text   = "My email is test@example.com and my SSN is 123-45-6789"
    val masked = PIIPatterns.maskAll(text)

    masked should include("[REDACTED_EMAIL]")
    masked should include("[REDACTED_SSN]")
    (masked should not).include("test@example.com")
    (masked should not).include("123-45-6789")
  }

  it should "return original text when no PII found" in {
    val text = "This is a normal text without any sensitive information"
    PIIPatterns.maskAll(text) shouldBe text
  }

  it should "provide summary of detected PII" in {
    val text    = "Contact test@example.com or user@domain.com, SSN: 123-45-6789"
    val summary = PIIPatterns.summarize(text)

    summary.get("Email") shouldBe Some(2)
    summary.get("SSN") shouldBe Some(1)
  }

  // ==========================================================================
  // PIIDetector Tests
  // ==========================================================================

  "PIIDetector" should "block when PII is detected (default mode)" in {
    val detector = PIIDetector()
    val result   = detector.validate("My SSN is 123-45-6789")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.message should include("PII detected")
  }

  it should "pass when no PII is present" in {
    val detector = PIIDetector()
    val result   = detector.validate("Hello, how can I help you today?")

    result shouldBe Right("Hello, how can I help you today?")
  }

  it should "mask PII when in Fix mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Fix)
    val result   = detector.validate("My email is test@example.com")

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_EMAIL]")
    (result.toOption.get should not).include("test@example.com")
  }

  it should "allow processing in Warn mode" in {
    val detector = PIIDetector(onFail = GuardrailAction.Warn)
    val result   = detector.validate("My SSN is 123-45-6789")

    // In warn mode, original text is returned
    result shouldBe Right("My SSN is 123-45-6789")
  }

  it should "detect only specified PII types" in {
    val emailOnlyDetector = PIIDetector(piiTypes = Seq(PIIType.Email))

    // Should detect email
    emailOnlyDetector.validate("Email: test@example.com").isLeft shouldBe true

    // Should not detect SSN (not in specified types)
    emailOnlyDetector.validate("SSN: 123-45-6789") shouldBe Right("SSN: 123-45-6789")
  }

  it should "work with strict preset" in {
    val detector = PIIDetector.strict
    val result   = detector.validate("IP: 192.168.1.1") // IP is in .all types

    result.isLeft shouldBe true
  }

  it should "work with masking preset" in {
    val detector = PIIDetector.masking
    val result   = detector.validate("Call 555-123-4567")

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_PHONE]")
  }

  it should "work with financial preset" in {
    val detector = PIIDetector.financial

    // Should block credit cards
    detector.validate("Card: 4111 1111 1111 1111").isLeft shouldBe true

    // Should not block email (not financial)
    detector.validate("Email: test@example.com") shouldBe Right("Email: test@example.com")
  }

  // ==========================================================================
  // PIIMasker Tests
  // ==========================================================================

  "PIIMasker" should "always mask PII and never block" in {
    val masker = PIIMasker()
    val text   = "SSN: 123-45-6789, Email: user@test.com, Phone: 555-123-4567"
    val result = masker.validate(text)

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_SSN]")
    result.toOption.get should include("[REDACTED_EMAIL]")
    result.toOption.get should include("[REDACTED_PHONE]")
  }

  it should "return unchanged text when no PII present" in {
    val masker = PIIMasker()
    val text   = "This is clean text with no sensitive data"
    val result = masker.validate(text)

    result shouldBe Right(text)
  }

  it should "mask only specified PII types" in {
    val masker = PIIMasker(Seq(PIIType.Email))
    val text   = "Email: test@example.com, SSN: 123-45-6789"
    val result = masker.validate(text)

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_EMAIL]")
    // SSN should NOT be masked since not in specified types
    result.toOption.get should include("123-45-6789")
  }

  it should "work with transform method" in {
    val masker = PIIMasker()
    val text   = "Contact me at user@test.com"

    masker.transform(text) should include("[REDACTED_EMAIL]")
    (masker.transform(text) should not).include("user@test.com")
  }

  it should "detect if text contains PII" in {
    val masker = PIIMasker()

    masker.containsPII("Email: test@example.com") shouldBe true
    masker.containsPII("No PII here") shouldBe false
  }

  it should "provide summary of PII to be masked" in {
    val masker  = PIIMasker()
    val summary = masker.summarizePII("Email: a@b.com, Phone: 555-123-4567")

    summary.get("Email") shouldBe Some(1)
    summary.get("Phone") shouldBe Some(1)
  }

  it should "work with financial preset" in {
    val masker = PIIMasker.financial
    val text   = "Card: 4111 1111 1111 1111"
    val result = masker.validate(text)

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_CARD]")
  }

  it should "work with contactInfo preset" in {
    val masker = PIIMasker.contactInfo
    val text   = "Email: test@test.com, SSN: 123-45-6789"
    val result = masker.validate(text)

    result.isRight shouldBe true
    result.toOption.get should include("[REDACTED_EMAIL]")
    // SSN not included in contactInfo preset
    result.toOption.get should include("123-45-6789")
  }

  // ==========================================================================
  // GuardrailAction Tests
  // ==========================================================================

  "GuardrailAction" should "have Block as default" in {
    GuardrailAction.default shouldBe GuardrailAction.Block
  }

  // ==========================================================================
  // GuardrailResult Tests
  // ==========================================================================

  "GuardrailResult" should "correctly report success status" in {
    import GuardrailResult._

    Passed("value").isSuccess shouldBe true
    Fixed("old", "new", Seq("fix")).isSuccess shouldBe true
    Warned("value", Seq("warn")).isSuccess shouldBe true
    Blocked(Seq("block")).isSuccess shouldBe false
  }

  it should "correctly report blocked status" in {
    import GuardrailResult._

    Passed("value").isBlocked shouldBe false
    Blocked(Seq("block")).isBlocked shouldBe true
  }

  it should "correctly report warnings" in {
    import GuardrailResult._

    Passed("value").hasWarnings shouldBe false
    Warned("value", Seq("warn")).hasWarnings shouldBe true
    Fixed("old", "new", Seq("fix")).hasWarnings shouldBe true
    Blocked(Seq("block")).hasWarnings shouldBe false
  }

  it should "extract value via toOption" in {
    import GuardrailResult._

    Passed("value").toOption shouldBe Some("value")
    Fixed("old", "new", Seq.empty).toOption shouldBe Some("new")
    Warned("value", Seq.empty).toOption shouldBe Some("value")
    Blocked(Seq.empty).toOption shouldBe None
  }

  // ==========================================================================
  // Integration: PIIDetector with CompositeGuardrail
  // ==========================================================================

  "PIIDetector" should "work with CompositeGuardrail.all" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new builtin.LengthCheck(1, 1000),
        PIIDetector()
      )
    )

    // Clean text should pass
    composite.validate("Hello world") shouldBe Right("Hello world")

    // Text with PII should fail
    composite.validate("My SSN is 123-45-6789").isLeft shouldBe true
  }

  it should "work in a pipeline with PIIMasker" in {
    // First mask, then validate (no PII should remain)
    val masker   = PIIMasker()
    val detector = PIIDetector()

    val originalText = "Contact me at test@example.com"
    val maskedText   = masker.transform(originalText)

    // Masked text should pass the detector
    detector.validate(maskedText) shouldBe Right(maskedText)
  }
}
