package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What `PIIPatterns.maskAll` does when the types it is given match overlapping stretches of the text.
 *
 * The types are matched independently, so overlaps are ordinary (a plain 16-digit card is also an account-shaped
 * run of digits). Before matches were merged, `maskAll` replaced each one by its original indices, which made a
 * later replacement cut the wrong characters or fail with a `StringIndexOutOfBoundsException`.
 */
class PIIPatternsMaskAllSpec extends AnyFlatSpec with Matchers {

  "PIIPatterns.maskAll" should "return a text with no match unchanged" in {
    PIIPatterns.maskAll("nothing to see here", PIIType.all) shouldBe "nothing to see here"
    PIIPatterns.maskAll("", PIIType.all) shouldBe ""
  }

  it should "replace two matches of the same stretch once, named by the type listed first" in {
    // "123456789" is both an SSN and a passport-shaped run
    PIIPatterns.maskAll("123456789", Seq(PIIType.SSN, PIIType.Passport)) shouldBe "[REDACTED_SSN]"
    PIIPatterns.maskAll("123456789", Seq(PIIType.Passport, PIIType.SSN)) shouldBe "[REDACTED_PASSPORT]"
  }

  it should "replace a match that contains another by the one that starts first, whatever the order of the types" in {
    // the phone number "+33 123456789" contains the passport-shaped run "123456789"
    PIIPatterns.maskAll("+33 123456789", Seq(PIIType.Passport, PIIType.Phone)) shouldBe "[REDACTED_PHONE]"
    PIIPatterns.maskAll("+33 123456789", Seq(PIIType.Phone, PIIType.Passport)) shouldBe "[REDACTED_PHONE]"
  }

  it should "merge two matches that overlap only in part, so no character of either survives" in {
    // the number ends in "0958", which is also where the email's local part starts
    val text = "+44 20 7946 0958.john@example.com"
    val out1 = PIIPatterns.maskAll(text, Seq(PIIType.Phone, PIIType.Email))
    val out2 = PIIPatterns.maskAll(text, Seq(PIIType.Email, PIIType.Phone))
    out1 shouldBe "[REDACTED_PHONE]"
    out2 shouldBe "[REDACTED_PHONE]"
    (out1 should not).include("john")
  }

  it should "keep the text before and after a merged stretch" in {
    PIIPatterns.maskAll("call +44 20 7946 0958.john@example.com now", Seq(PIIType.Phone, PIIType.Email)) shouldBe
      "call [REDACTED_PHONE] now"
  }

  it should "merge a chain of overlapping matches into one stretch" in {
    // "10005 3782" is SSN-shaped, so it overlaps the end of the first card and the start of the second
    PIIPatterns.maskAll("3782 822463 10005 3782 822463 10005", Seq(PIIType.CreditCard, PIIType.SSN)) shouldBe
      "[REDACTED_CARD]"
    PIIPatterns.maskAll("3782 822463 10005 3782 822463 10005", Seq(PIIType.CreditCard)) shouldBe
      "[REDACTED_CARD] [REDACTED_CARD]"
  }

  it should "not merge two matches that touch without overlapping" in {
    // "a@b.com" ends where "+44 20 7946 0958" begins
    PIIPatterns.maskAll("a@b.com+44 20 7946 0958", Seq(PIIType.Email, PIIType.Phone)) shouldBe
      "[REDACTED_EMAIL][REDACTED_PHONE]"
  }

  it should "mask separate matches of different types in one text" in {
    PIIPatterns.maskAll(
      "a@b.com, 123-45-6789 and 192.168.1.100",
      Seq(PIIType.Email, PIIType.SSN, PIIType.IPAddress)
    ) shouldBe
      "[REDACTED_EMAIL], [REDACTED_SSN] and [REDACTED_IP]"
  }

  it should "not fail on a card number that an account pattern also matches" in {
    // this input made maskAll throw a StringIndexOutOfBoundsException when both types were given
    PIIPatterns.maskAll("4111111111111111", Seq(PIIType.CreditCard, PIIType.BankAccount)) shouldBe "[REDACTED_CARD]"
    PIIPatterns.maskAll("4111111111111111", Seq(PIIType.BankAccount, PIIType.CreditCard)) shouldBe "[REDACTED_ACCOUNT]"
  }

  it should "give the same text for the same matches however many types are given" in {
    val text = "mail a@b.com, SSN 123-45-6789"
    PIIPatterns.maskAll(text, Seq(PIIType.Email, PIIType.SSN)) shouldBe
      PIIPatterns.maskAll(text, Seq(PIIType.Email, PIIType.SSN, PIIType.CreditCard))
  }
}
