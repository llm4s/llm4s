package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PIIPatternsSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // SSN Detection
  // ==========================================================================

  "SSN pattern" should "detect SSN with dashes" in {
    PIIType.SSN.findAll("123-45-6789") should have size 1
  }

  it should "detect SSN with spaces" in {
    PIIType.SSN.findAll("123 45 6789") should have size 1
  }

  it should "detect SSN with no separators" in {
    PIIType.SSN.findAll("123456789") should have size 1
  }

  it should "detect SSN embedded in text" in {
    val matches = PIIType.SSN.findAll("My SSN is 123-45-6789 for records")
    matches should have size 1
    matches.head.value shouldBe "123-45-6789"
  }

  it should "detect SSN with mixed separators" in {
    PIIType.SSN.findAll("123-45 6789") should have size 1
  }

  it should "detect SSN at start of text" in {
    PIIType.SSN.findAll("123-45-6789 is my number") should have size 1
  }

  it should "detect SSN at end of text" in {
    PIIType.SSN.findAll("My number is 123-45-6789") should have size 1
  }

  it should "reject SSN with 000 prefix" in {
    PIIType.SSN.findAll("000-12-3456") shouldBe empty
  }

  it should "reject SSN with 666 prefix" in {
    PIIType.SSN.findAll("666-12-3456") shouldBe empty
  }

  it should "reject SSN with 9xx prefix" in {
    PIIType.SSN.findAll("900-12-3456") shouldBe empty
    PIIType.SSN.findAll("999-12-3456") shouldBe empty
  }

  it should "reject SSN with 00 group number" in {
    PIIType.SSN.findAll("123-00-6789") shouldBe empty
  }

  it should "reject SSN with 0000 serial number" in {
    PIIType.SSN.findAll("123-45-0000") shouldBe empty
  }

  // ==========================================================================
  // Credit Card Detection
  // ==========================================================================

  "CreditCard pattern" should "detect Visa with no separators" in {
    PIIType.CreditCard.findAll("4111111111111111") should have size 1
  }

  it should "detect Visa with dashes" in {
    PIIType.CreditCard.findAll("4111-1111-1111-1111") should have size 1
  }

  it should "detect Visa with spaces" in {
    PIIType.CreditCard.findAll("4111 1111 1111 1111") should have size 1
  }

  it should "detect MasterCard 51-55 range" in {
    PIIType.CreditCard.findAll("5500-0000-0000-0004") should have size 1
    PIIType.CreditCard.findAll("5100-0000-0000-0000") should have size 1
  }

  it should "detect MasterCard 2-series range" in {
    PIIType.CreditCard.findAll("2221-0000-0000-0000") should have size 1
    PIIType.CreditCard.findAll("2720-0000-0000-0000") should have size 1
  }

  it should "detect Discover 6011 prefix" in {
    PIIType.CreditCard.findAll("6011-0000-0000-0004") should have size 1
  }

  it should "detect Discover 65 prefix" in {
    PIIType.CreditCard.findAll("6500-0000-0000-0000") should have size 1
  }

  it should "reject card with invalid 1xxx prefix" in {
    PIIType.CreditCard.findAll("1111-1111-1111-1111") shouldBe empty
  }

  it should "reject card with invalid 8xxx prefix" in {
    PIIType.CreditCard.findAll("8111-1111-1111-1111") shouldBe empty
  }

  it should "reject card with invalid 0xxx prefix" in {
    PIIType.CreditCard.findAll("0111-1111-1111-1111") shouldBe empty
  }

  it should "reject card number that is too short" in {
    PIIType.CreditCard.findAll("4111-1111-1111") shouldBe empty
  }

  it should "reject card number that is too long" in {
    PIIType.CreditCard.findAll("41111111111111111") shouldBe empty
  }

  it should "reject card number embedded in longer digit sequence" in {
    PIIType.CreditCard.findAll("941111111111111119") shouldBe empty
  }

  // Known limitation: Amex uses 15-digit format (4-6-5), regex expects 16-digit (4-4-4-4)
  it should "not match Amex 15-digit format (known limitation)" in {
    PIIType.CreditCard.findAll("3782 822463 10005") shouldBe empty
  }

  // ==========================================================================
  // Email Detection
  // ==========================================================================

  "Email pattern" should "detect simple email" in {
    PIIType.Email.findAll("user@example.com") should have size 1
  }

  it should "detect plus-addressing" in {
    PIIType.Email.findAll("user+tag@example.com") should have size 1
  }

  it should "detect subdomain email" in {
    PIIType.Email.findAll("user@mail.example.com") should have size 1
  }

  it should "detect long TLD" in {
    PIIType.Email.findAll("user@example.museum") should have size 1
  }

  it should "detect dots in local part" in {
    PIIType.Email.findAll("first.last@example.com") should have size 1
  }

  it should "detect hyphen in domain" in {
    PIIType.Email.findAll("user@my-domain.com") should have size 1
  }

  it should "reject string without @" in {
    PIIType.Email.findAll("userexample.com") shouldBe empty
  }

  it should "reject @ without domain" in {
    PIIType.Email.findAll("user@") shouldBe empty
  }

  it should "reject @ without local part" in {
    PIIType.Email.findAll("@example.com") shouldBe empty
  }

  it should "reject email with spaces" in {
    PIIType.Email.findAll("user @example.com") shouldBe empty
  }

  it should "reject domain without TLD dot" in {
    PIIType.Email.findAll("user@example") shouldBe empty
  }

  it should "reject single-char TLD" in {
    PIIType.Email.findAll("user@example.c") shouldBe empty
  }

  // ==========================================================================
  // Phone Detection
  // ==========================================================================

  "Phone pattern" should "detect parenthesized area code" in {
    PIIType.Phone.findAll("(555) 123-4567") should have size 1
  }

  it should "detect dashes format" in {
    PIIType.Phone.findAll("555-123-4567") should have size 1
  }

  it should "detect dots format" in {
    PIIType.Phone.findAll("555.123.4567") should have size 1
  }

  it should "detect +1 prefix" in {
    PIIType.Phone.findAll("+1 555-123-4567") should have size 1
  }

  it should "detect +1 with dot separator" in {
    PIIType.Phone.findAll("+1.555.123.4567") should have size 1
  }

  it should "detect no separators" in {
    PIIType.Phone.findAll("5551234567") should have size 1
  }

  it should "reject too few digits" in {
    PIIType.Phone.findAll("555-123") shouldBe empty
  }

  it should "reject too many digits" in {
    PIIType.Phone.findAll("55512345678901") shouldBe empty
  }

  it should "reject partial number" in {
    PIIType.Phone.findAll("555-12") shouldBe empty
  }

  it should "reject phone embedded in longer digit sequence" in {
    PIIType.Phone.findAll("95551234567890") shouldBe empty
  }

  it should "reject short number" in {
    PIIType.Phone.findAll("12345") shouldBe empty
  }

  it should "reject letters mixed in" in {
    PIIType.Phone.findAll("555-ABC-4567") shouldBe empty
  }

  // ==========================================================================
  // IP Address Detection
  // ==========================================================================

  "IPAddress pattern" should "detect private 192.168.x.x" in {
    PIIType.IPAddress.findAll("192.168.1.1") should have size 1
  }

  it should "detect loopback" in {
    PIIType.IPAddress.findAll("127.0.0.1") should have size 1
  }

  it should "detect 0.0.0.0" in {
    PIIType.IPAddress.findAll("0.0.0.0") should have size 1
  }

  it should "detect 255.255.255.255" in {
    PIIType.IPAddress.findAll("255.255.255.255") should have size 1
  }

  it should "detect class A address" in {
    PIIType.IPAddress.findAll("10.0.0.1") should have size 1
  }

  it should "detect single-digit octets" in {
    PIIType.IPAddress.findAll("1.2.3.4") should have size 1
  }

  it should "reject octet over 255 (first)" in {
    PIIType.IPAddress.findAll("256.1.1.1") shouldBe empty
  }

  it should "reject octet over 255 (last)" in {
    PIIType.IPAddress.findAll("1.1.1.256") shouldBe empty
  }

  it should "reject missing octet" in {
    PIIType.IPAddress.findAll("192.168.1") shouldBe empty
  }

  // Known limitation: "192.168.1.1.1" matches "192.168.1.1" as the regex uses lookahead/behind for digits only
  it should "still find a valid IP within too-many-octet string (known limitation)" in {
    PIIType.IPAddress.findAll("192.168.1.1.1") should have size 1
  }

  it should "reject IP embedded in longer digit sequence" in {
    PIIType.IPAddress.findAll("9192.168.1.19") shouldBe empty
  }

  // Known limitation: version numbers like "1.2.3.4" match IP pattern
  it should "match version-number-like strings (known limitation)" in {
    PIIType.IPAddress.findAll("version 1.2.3.4") should have size 1
  }

  // ==========================================================================
  // Passport Detection
  // ==========================================================================

  "Passport pattern" should "detect 9-digit number" in {
    PIIType.Passport.findAll("123456789") should have size 1
  }

  it should "detect 8-digit number" in {
    PIIType.Passport.findAll("12345678") should have size 1
  }

  it should "detect letter prefix with 8 digits" in {
    PIIType.Passport.findAll("A12345678") should have size 1
  }

  it should "detect passport embedded in text" in {
    val matches = PIIType.Passport.findAll("Passport: 123456789 issued")
    matches should have size 1
    matches.head.value shouldBe "123456789"
  }

  it should "detect letter prefix with 9 digits" in {
    PIIType.Passport.findAll("B123456789") should have size 1
  }

  it should "reject 7 digits" in {
    PIIType.Passport.findAll("1234567") shouldBe empty
  }

  it should "reject number embedded in alphanumeric string" in {
    PIIType.Passport.findAll("ABC12345678DEF") shouldBe empty
  }

  it should "reject lowercase prefix" in {
    PIIType.Passport.findAll("a12345678") shouldBe empty
  }

  it should "reject multiple letter prefix" in {
    PIIType.Passport.findAll("AB12345678") shouldBe empty
  }

  it should "reject 10+ digit number" in {
    // 10+ digits falls into BankAccount range but should be rejected for passport
    // The passport regex requires 8-9 digits with optional single uppercase letter prefix
    PIIType.Passport.findAll("12345678901") shouldBe empty
  }

  // ==========================================================================
  // Date of Birth Detection
  // ==========================================================================

  "DateOfBirth pattern" should "detect MM/DD/YYYY" in {
    PIIType.DateOfBirth.findAll("01/15/1990") should have size 1
  }

  it should "detect M/D/YYYY" in {
    PIIType.DateOfBirth.findAll("1/5/1990") should have size 1
  }

  it should "detect YYYY-MM-DD" in {
    PIIType.DateOfBirth.findAll("1990-01-15") should have size 1
  }

  it should "detect MM-DD-YYYY" in {
    PIIType.DateOfBirth.findAll("01-15-1990") should have size 1
  }

  it should "detect 20xx year" in {
    PIIType.DateOfBirth.findAll("03/15/2005") should have size 1
  }

  it should "detect date embedded in text" in {
    val matches = PIIType.DateOfBirth.findAll("Born on 01/15/1990 in NY")
    matches should have size 1
    matches.head.value shouldBe "01/15/1990"
  }

  it should "reject month over 12" in {
    PIIType.DateOfBirth.findAll("13/15/1990") shouldBe empty
  }

  it should "reject day over 31" in {
    PIIType.DateOfBirth.findAll("01/32/1990") shouldBe empty
  }

  it should "reject year before 1900" in {
    PIIType.DateOfBirth.findAll("01/15/1899") shouldBe empty
  }

  it should "reject year after 2099" in {
    PIIType.DateOfBirth.findAll("01/15/2100") shouldBe empty
  }

  it should "reject partial date" in {
    PIIType.DateOfBirth.findAll("01/15") shouldBe empty
  }

  it should "reject date embedded in longer digit sequence" in {
    PIIType.DateOfBirth.findAll("901/15/19901") shouldBe empty
  }

  // ==========================================================================
  // Bank Account Detection
  // ==========================================================================

  "BankAccount pattern" should "detect 8-digit number" in {
    PIIType.BankAccount.findAll("12345678") should have size 1
  }

  it should "detect 10-digit number" in {
    PIIType.BankAccount.findAll("1234567890") should have size 1
  }

  it should "detect 12-digit number" in {
    PIIType.BankAccount.findAll("123456789012") should have size 1
  }

  it should "detect 17-digit number" in {
    PIIType.BankAccount.findAll("12345678901234567") should have size 1
  }

  it should "detect account embedded in text" in {
    val matches = PIIType.BankAccount.findAll("Account: 1234567890 balance")
    matches should have size 1
    matches.head.value shouldBe "1234567890"
  }

  it should "reject 7-digit number" in {
    PIIType.BankAccount.findAll("1234567") shouldBe empty
  }

  it should "reject 18-digit number" in {
    PIIType.BankAccount.findAll("123456789012345678") shouldBe empty
  }

  it should "reject number embedded in longer digits" in {
    PIIType.BankAccount.findAll("912345678901234567890") shouldBe empty
  }

  it should "not be in default types" in {
    PIIType.default should not contain PIIType.BankAccount
  }

  it should "be in sensitive types" in {
    PIIType.sensitive should contain(PIIType.BankAccount)
  }

  // ==========================================================================
  // Multi-PII and Edge Cases
  // ==========================================================================

  "PIIPatterns.detect" should "find multiple PII types in one text" in {
    val text    = "Email: test@example.com SSN: 123-45-6789"
    val matches = PIIPatterns.detect(text, PIIType.all)
    val types   = matches.map(_.piiType).toSet
    types should contain(PIIType.Email)
    types should contain(PIIType.SSN)
  }

  it should "find adjacent PII" in {
    val text    = "test@example.com 123-45-6789"
    val matches = PIIPatterns.detect(text, PIIType.all)
    matches.size should be >= 2
  }

  it should "find PII in parentheses" in {
    val text    = "(test@example.com)"
    val matches = PIIPatterns.detect(text, Seq(PIIType.Email))
    matches should have size 1
  }

  it should "find PII in quotes" in {
    val text    = "\"test@example.com\""
    val matches = PIIPatterns.detect(text, Seq(PIIType.Email))
    matches should have size 1
  }

  it should "return empty for empty string" in {
    PIIPatterns.detect("", PIIType.all) shouldBe empty
  }

  it should "return empty for whitespace" in {
    PIIPatterns.detect("   \t\n  ", PIIType.all) shouldBe empty
  }

  it should "populate PIIMatch fields correctly" in {
    val matches = PIIPatterns.detect("SSN: 123-45-6789", Seq(PIIType.SSN))
    matches should have size 1
    val m = matches.head
    m.piiType shouldBe PIIType.SSN
    m.value shouldBe "123-45-6789"
    m.startIndex shouldBe 5
    m.endIndex shouldBe 16
    m.maskedValue shouldBe "[REDACTED_SSN]"
  }

  // ==========================================================================
  // False Positive Awareness (Known Limitations)
  // ==========================================================================

  "PIIPatterns (false positives)" should "match ZIP+4 as SSN (known limitation)" in {
    // ZIP+4 like 123-45-6789 matches SSN pattern
    PIIPatterns.containsPII("ZIP: 123-45-6789", Seq(PIIType.SSN)) shouldBe true
  }

  it should "match version number as IP (known limitation)" in {
    PIIPatterns.containsPII("v1.2.3.4", Seq(PIIType.IPAddress)) shouldBe true
  }

  it should "match invalid calendar date as DOB (known limitation)" in {
    // Feb 30 is not a real date but matches the regex
    PIIPatterns.containsPII("02/30/1990", Seq(PIIType.DateOfBirth)) shouldBe true
  }

  it should "match order number as BankAccount (known limitation)" in {
    // Long order numbers match the broad bank account pattern
    PIIPatterns.containsPII("Order #12345678", Seq(PIIType.BankAccount)) shouldBe true
  }

  // ==========================================================================
  // PIIType Collections
  // ==========================================================================

  "PIIType.default" should "contain SSN, CreditCard, Email, Phone" in {
    PIIType.default should contain theSameElementsAs Seq(
      PIIType.SSN,
      PIIType.CreditCard,
      PIIType.Email,
      PIIType.Phone
    )
  }

  "PIIType.all" should "contain 7 types (excluding BankAccount from val)" in {
    PIIType.all should have size 7
    PIIType.all should contain(PIIType.SSN)
    PIIType.all should contain(PIIType.CreditCard)
    PIIType.all should contain(PIIType.Email)
    PIIType.all should contain(PIIType.Phone)
    PIIType.all should contain(PIIType.IPAddress)
    PIIType.all should contain(PIIType.Passport)
    PIIType.all should contain(PIIType.DateOfBirth)
  }

  "PIIType.sensitive" should "contain SSN, CreditCard, Email, Phone, BankAccount" in {
    PIIType.sensitive should contain theSameElementsAs Seq(
      PIIType.SSN,
      PIIType.CreditCard,
      PIIType.Email,
      PIIType.Phone,
      PIIType.BankAccount
    )
  }

  "PIIType name properties" should "be defined for all types" in {
    PIIType.SSN.name shouldBe "SSN"
    PIIType.CreditCard.name shouldBe "Credit Card"
    PIIType.Email.name shouldBe "Email"
    PIIType.Phone.name shouldBe "Phone"
    PIIType.IPAddress.name shouldBe "IP Address"
    PIIType.Passport.name shouldBe "Passport"
    PIIType.DateOfBirth.name shouldBe "Date of Birth"
    PIIType.BankAccount.name shouldBe "Bank Account"
  }

  // ==========================================================================
  // maskAll and summarize
  // ==========================================================================

  "PIIPatterns.maskAll" should "mask multiple PII types" in {
    val text   = "Email: test@example.com SSN: 123-45-6789"
    val masked = PIIPatterns.maskAll(text)
    masked should include("[REDACTED_EMAIL]")
    masked should include("[REDACTED_SSN]")
    (masked should not).include("test@example.com")
    (masked should not).include("123-45-6789")
  }

  it should "mask adjacent PII correctly" in {
    val text   = "test@example.com 123-45-6789"
    val masked = PIIPatterns.maskAll(text)
    masked should include("[REDACTED_EMAIL]")
    masked should include("[REDACTED_SSN]")
  }

  "PIIPatterns.summarize" should "count multiple matches per type" in {
    val text    = "a@b.com and c@d.com and SSN 123-45-6789"
    val summary = PIIPatterns.summarize(text)
    summary("Email") shouldBe 2
    summary("SSN") shouldBe 1
  }

  it should "return empty map for clean text" in {
    PIIPatterns.summarize("no pii here") shouldBe empty
  }
}
