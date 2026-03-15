package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.PIIMasker
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PIIMaskerSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // 1. Masking Each PII Type via PIIMasker
  // ==========================================================================

  "PIIMasker.all" should "mask SSN" in {
    val masker = PIIMasker.all
    val result = masker.transform("My SSN is 123-45-6789")

    result should include("[REDACTED_SSN]")
    (result should not).include("123-45-6789")
  }

  it should "mask credit card numbers" in {
    val masker = PIIMasker.all
    val result = masker.transform("Card: 4111-1111-1111-1111")

    result should include("[REDACTED_CARD]")
    (result should not).include("4111-1111-1111-1111")
  }

  it should "mask email addresses" in {
    val masker = PIIMasker.all
    val result = masker.transform("Email: user@example.com")

    result should include("[REDACTED_EMAIL]")
    (result should not).include("user@example.com")
  }

  it should "mask phone numbers" in {
    val masker = PIIMasker.all
    val result = masker.transform("Call (555) 123-4567")

    result should include("[REDACTED_PHONE]")
    (result should not).include("(555) 123-4567")
  }

  it should "mask IP addresses" in {
    val masker = PIIMasker.all
    val result = masker.transform("Server at 192.168.1.1")

    result should include("[REDACTED_IP]")
    (result should not).include("192.168.1.1")
  }

  it should "mask passport numbers" in {
    val masker = PIIMasker.all
    val result = masker.transform("Passport: A12345678")

    result should include("[REDACTED_PASSPORT]")
    (result should not).include("A12345678")
  }

  it should "mask dates of birth" in {
    val masker = PIIMasker.all
    val result = masker.transform("DOB: 01/15/1990")

    result should include("[REDACTED_DOB]")
    (result should not).include("01/15/1990")
  }

  // ==========================================================================
  // 2. Irreversibility
  // ==========================================================================

  "PIIMasker" should "produce output that does not contain original SSN" in {
    val masker = PIIMasker.all
    val result = masker.transform("SSN: 123-45-6789")

    (result should not).include("123-45-6789")
    (result should not).include("123456789")
  }

  it should "produce output that does not contain original email" in {
    val masker = PIIMasker.all
    val result = masker.transform("Contact: alice@secret.org")

    (result should not).include("alice@secret.org")
    (result should not).include("alice")
  }

  it should "produce output that does not contain original credit card" in {
    val masker = PIIMasker.all
    val result = masker.transform("Pay with 4111111111111111")

    (result should not).include("4111111111111111")
    (result should not).include("4111")
  }

  it should "produce output that does not contain original phone number" in {
    val masker = PIIMasker.all
    val result = masker.transform("Phone: 555-123-4567")

    (result should not).include("555-123-4567")
  }

  it should "produce output that does not contain original IP address" in {
    val masker = PIIMasker.all
    val result = masker.transform("IP: 192.168.1.1")

    (result should not).include("192.168.1.1")
  }

  it should "produce redaction placeholders with no recoverable information" in {
    val masker = PIIMasker.all
    val result = masker.transform("SSN: 123-45-6789, Email: user@test.com")

    // Placeholders are generic, containing no part of original values
    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_EMAIL]")
    (result should not).include("123")
    (result should not).include("user")
  }

  // ==========================================================================
  // 3. Multiple PII Instances in Single Text
  // ==========================================================================

  it should "mask multiple different PII types in one string" in {
    val masker = PIIMasker.all
    val text   = "SSN: 123-45-6789, Email: a@b.com, Phone: 555-123-4567, IP: 10.0.0.1"
    val result = masker.transform(text)

    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_EMAIL]")
    result should include("[REDACTED_PHONE]")
    result should include("[REDACTED_IP]")
  }

  it should "mask multiple instances of the same PII type" in {
    val masker = PIIMasker.all
    val text   = "Contact alice@one.com or bob@two.com for help"
    val result = masker.transform(text)

    (result should not).include("alice@one.com")
    (result should not).include("bob@two.com")
    // Both should be replaced with the same placeholder type
    result should include("[REDACTED_EMAIL]")
  }

  it should "mask multiple SSNs in the same text" in {
    val masker = PIIMasker.all
    val text   = "Applicant: 123-45-6789, Spouse: 234-56-7890"
    val result = masker.transform(text)

    (result should not).include("123-45-6789")
    (result should not).include("234-56-7890")
  }

  it should "report correct counts via summarizePII for multiple instances" in {
    val masker  = PIIMasker.all
    val text    = "Emails: a@b.com and c@d.com, SSN: 123-45-6789"
    val summary = masker.summarizePII(text)

    summary.get("Email") shouldBe Some(2)
    summary.get("SSN") shouldBe Some(1)
  }

  // ==========================================================================
  // 4. Masking Idempotency
  // ==========================================================================

  it should "be idempotent: re-masking already-masked text produces identical output" in {
    val masker     = PIIMasker.all
    val original   = "My SSN is 123-45-6789 and email is test@test.com"
    val firstPass  = masker.transform(original)
    val secondPass = masker.transform(firstPass)

    secondPass shouldBe firstPass
  }

  it should "only mask real PII when text contains both real PII and existing placeholders" in {
    val masker = PIIMasker.all
    val text   = "[REDACTED_SSN] and also email: real@email.com"
    val result = masker.transform(text)

    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_EMAIL]")
    (result should not).include("real@email.com")
  }

  // ==========================================================================
  // 5. Unicode Content Handling
  // ==========================================================================

  it should "preserve Unicode characters surrounding PII" in {
    val masker = PIIMasker.all
    val text   = "\u00dcber wichtig: SSN 123-45-6789 geh\u00f6rt dazu"
    val result = masker.transform(text)

    result should include("\u00dcber wichtig:")
    result should include("geh\u00f6rt dazu")
    result should include("[REDACTED_SSN]")
  }

  it should "detect PII embedded in Unicode-heavy text" in {
    val masker = PIIMasker.all
    val text   = "\u3053\u3093\u306b\u3061\u306f user@example.com \u3055\u3088\u3046\u306a\u3089"
    val result = masker.transform(text)

    result should include("[REDACTED_EMAIL]")
    (result should not).include("user@example.com")
    result should include("\u3053\u3093\u306b\u3061\u306f")
    result should include("\u3055\u3088\u3046\u306a\u3089")
  }

  it should "not corrupt non-ASCII characters when masking PII" in {
    val masker = PIIMasker.all
    val text   = "R\u00e9sum\u00e9 de 555-123-4567 caf\u00e9"
    val result = masker.transform(text)

    result should include("R\u00e9sum\u00e9 de")
    result should include("caf\u00e9")
    result should include("[REDACTED_PHONE]")
  }

  // ==========================================================================
  // 6. Remaining Presets
  // ==========================================================================

  "PIIMasker.all preset" should "mask all types including IP, Passport, and DOB" in {
    val masker = PIIMasker.all
    val text   = "IP: 10.0.0.1, Passport: A12345678, DOB: 03/25/1985, SSN: 123-45-6789, Email: a@b.com"
    val result = masker.transform(text)

    result should include("[REDACTED_IP]")
    result should include("[REDACTED_PASSPORT]")
    result should include("[REDACTED_DOB]")
    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_EMAIL]")
  }

  it should "include all types from PIIType.all" in {
    val masker = PIIMasker.all
    masker.piiTypes shouldBe PIIType.all
  }

  "PIIMasker.identity preset" should "mask SSN, Passport, and DOB" in {
    val masker = PIIMasker.identity
    val text   = "SSN: 123-45-6789, Passport: A12345678, DOB: 01/15/1990"
    val result = masker.transform(text)

    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_PASSPORT]")
    result should include("[REDACTED_DOB]")
  }

  it should "not mask email or phone" in {
    val masker = PIIMasker.identity
    val text   = "Email: test@test.com, Phone: 555-123-4567"
    val result = masker.transform(text)

    result should include("test@test.com")
    result should include("555-123-4567")
  }

  it should "contain exactly SSN, Passport, and DateOfBirth types" in {
    val masker = PIIMasker.identity
    masker.piiTypes shouldBe Seq(PIIType.SSN, PIIType.Passport, PIIType.DateOfBirth)
  }

  "PIIMasker.sensitive preset" should "mask SSN, CreditCard, Email, Phone, and BankAccount" in {
    val masker = PIIMasker.sensitive
    masker.piiTypes shouldBe PIIType.sensitive
    masker.piiTypes should contain(PIIType.SSN)
    masker.piiTypes should contain(PIIType.CreditCard)
    masker.piiTypes should contain(PIIType.Email)
    masker.piiTypes should contain(PIIType.Phone)
    masker.piiTypes should contain(PIIType.BankAccount)
  }

  it should "mask SSN and credit card in text" in {
    val masker = PIIMasker.sensitive
    val text   = "SSN: 123-45-6789, Card: 4111-1111-1111-1111"
    val result = masker.transform(text)

    result should include("[REDACTED_SSN]")
    result should include("[REDACTED_CARD]")
  }

  it should "not mask IP or DOB (not in sensitive preset)" in {
    val masker = PIIMasker.sensitive
    val text   = "IP: 192.168.1.1, DOB: 01/15/1990"
    val result = masker.transform(text)

    result should include("192.168.1.1")
    result should include("01/15/1990")
    masker.piiTypes should not contain PIIType.IPAddress
    masker.piiTypes should not contain PIIType.Passport
    masker.piiTypes should not contain PIIType.DateOfBirth
  }

  // ==========================================================================
  // 7. Edge Cases
  // ==========================================================================

  "PIIMasker edge cases" should "handle empty string input" in {
    val masker = PIIMasker.all
    masker.transform("") shouldBe ""
    masker.validate("") shouldBe Right("")
    masker.containsPII("") shouldBe false
    masker.summarizePII("") shouldBe empty
  }

  it should "pass through text with no PII unchanged" in {
    val masker = PIIMasker.all
    val text   = "Hello, this is a normal sentence with no sensitive data."
    masker.transform(text) shouldBe text
  }

  it should "have name 'PIIMasker'" in {
    val masker = PIIMasker()
    masker.name shouldBe "PIIMasker"
  }

  it should "include configured PII type names in description" in {
    val masker = PIIMasker(Seq(PIIType.Email, PIIType.Phone))
    masker.description shouldBe defined
    masker.description.get should include("Email")
    masker.description.get should include("Phone")
  }

  it should "validate returns Right for all inputs (never blocks)" in {
    val masker      = PIIMasker.all
    val textWithPII = "SSN: 123-45-6789, Email: test@test.com, IP: 10.0.0.1"

    masker.validate(textWithPII).isRight shouldBe true
    masker.validate("clean text").isRight shouldBe true
    masker.validate("").isRight shouldBe true
  }
}
