package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.builtin.{ PIIDetector, PIIMasker, SecretLeakGuardrail }
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.agent.guardrails.patterns.SecretPatterns.SecretType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The PII and secret patterns match a normalised copy of the text (NFKC, format characters and combining marks
 * removed, dashes as `-`), and report and mask at the original's indices (#3244).
 */
class PIIPatternsNormalisationSpec extends AnyFlatSpec with Matchers with org.scalatest.LoneElement {

  import PIIPatternsNormalisationSpec._

  ssnVariants.foreach { case (name, ssn) =>
    "PIIPatterns" should s"find an SSN written with $name" in {
      val text    = s"my ssn is $ssn ok"
      val matches = PIIType.SSN.findAll(text)
      matches.map(_.value) shouldBe Seq(ssn)
      PIIPatterns.maskAll(text, Seq(PIIType.SSN)) shouldBe "my ssn is [REDACTED_SSN] ok"
    }
  }

  cardVariants.foreach { case (name, card) =>
    it should s"find a card number written with $name" in {
      val text = s"card: $card."
      PIIType.CreditCard.findAll(text).map(_.value) shouldBe Seq(card)
      PIIPatterns.maskAll(text, Seq(PIIType.CreditCard)) shouldBe "card: [REDACTED_CARD]."
    }
  }

  it should "find an email written with a fullwidth @ or ." in {
    Seq("alice\uFF20example.com", "alice@example\uFF0Ecom", fullwidth("alice@example.com")).foreach { email =>
      val text = s"mail $email now"
      PIIType.Email.findAll(text).map(_.value) shouldBe Seq(email)
      PIIPatterns.maskAll(text, Seq(PIIType.Email)) shouldBe "mail [REDACTED_EMAIL] now"
    }
  }

  it should "leave the text around a match, including invisible characters outside it, unchanged" in {
    val text = s"x$zwsp 123-45-6789 ${zwsp}\u00E9y"
    PIIPatterns.maskAll(text) shouldBe s"x$zwsp [REDACTED_SSN] ${zwsp}\u00E9y"
  }

  it should "report the original indices of a match after a character NFKC expands" in {
    // U+FB01 (fi ligature) becomes two characters in the copy
    val text = "\uFB01le 123-45-6789"
    val m    = PIIType.SSN.findAll(text).loneElement
    (m.startIndex, m.endIndex) shouldBe ((4, 15))
    text.substring(m.startIndex, m.endIndex) shouldBe "123-45-6789"
  }

  it should "keep matching plain ASCII as before" in {
    PIIPatterns.maskAll("call 555-123-4567 or a@b.io") shouldBe "call [REDACTED_PHONE] or [REDACTED_EMAIL]"
  }

  digitLike.foreach { after =>
    it should s"keep a match the original holds when NFKC turns the ${after.codePointAt(0).toHexString} after it into a digit" in {
      Seq(
        PIIType.SSN        -> "123-45-6789",
        PIIType.CreditCard -> "4111-1111-1111-1111",
        PIIType.Phone      -> "555-123-4567"
      ).foreach { case (piiType, value) =>
        val text = s"id: $value$after end"
        withClue(s"${piiType.name}: ") {
          piiType.findAll(text).map(_.value) shouldBe Seq(value)
          PIIPatterns.maskAll(text, Seq(piiType)) shouldBe s"id: ${piiType.mask(value)}$after end"
        }
      }
      PIIDetector().validate(s"SSN: 123-45-6789$after").isLeft shouldBe true
      PIIMasker().validate(s"SSN: 123-45-6789$after") shouldBe Right(s"SSN: [REDACTED_SSN]$after")
    }
  }

  it should "report a stretch both the original and the copy match once" in {
    val text = s"123-45-6789 and 123-45-${zwsp}6789"
    PIIType.SSN.findAll(text).map(_.value) shouldBe Seq("123-45-6789", s"123-45-${zwsp}6789")
    PIIPatterns.summarize(text, Seq(PIIType.SSN)) shouldBe Map("SSN" -> 2)
  }

  "PIIMasker and PIIDetector" should "mask and block the variants" in {
    PIIMasker().validate(s"ssn 123-45-${zwsp}6789") shouldBe Right("ssn [REDACTED_SSN]")
    PIIDetector().validate("card 4111.1111.1111.1111").isLeft shouldBe true
  }

  "SecretPatterns" should "keep a key the original holds whatever NFKC makes of the characters around it" in {
    val key = "AKIAIOSFODNN7EXAMPLE"
    digitLike.foreach { after =>
      val text = s"$after$key$after"
      SecretType.AwsAccessKey.findAll(text).map(_.value) shouldBe Seq(key)
      SecretPatterns.redactAll(text) shouldBe s"${after}[REDACTED_AWS_KEY]$after"
    }
  }

  it should "find a key split by a zero-width space or written in fullwidth" in {
    val key = "AKIAIOSFODNN7EXAMPLE"
    Seq(key.patch(8, zwsp, 0), fullwidth(key)).foreach { variant =>
      val text = s"key=$variant;"
      SecretType.AwsAccessKey.findAll(text).map(_.value) shouldBe Seq(variant)
      SecretPatterns.redactAll(text) shouldBe "key=[REDACTED_AWS_KEY];"
      SecretLeakGuardrail().validate(text).isLeft shouldBe true
    }
  }
}

object PIIPatternsNormalisationSpec {

  // NFKC turns each of these into a digit, which would join the number before it: superscript two, circled one,
  // mathematical bold nine (a surrogate pair)
  val digitLike = Seq("\u00B2", "\u2460", "\uD835\uDFD7")

  val zwsp = "\u200B"

  def fullwidth(s: String): String =
    s.map(c => if (c >= '!' && c <= '~') (c + 0xfee0).toChar else c)

  val ssnVariants = Seq(
    "a zero-width space"   -> s"123-45-${zwsp}6789",
    "fullwidth digits"     -> fullwidth("123-45-6789"),
    "dots"                 -> "123.45.6789",
    "non-breaking hyphens" -> "123\u201145\u20116789",
    "a soft hyphen"        -> "123-4\u00AD5-6789"
  )

  val cardVariants = Seq(
    "dots"               -> "4111.1111.1111.1111",
    "a zero-width space" -> s"4111 11${zwsp}11 1111 1111",
    "en dashes"          -> "4111\u20131111\u20131111\u20131111",
    "fullwidth digits"   -> fullwidth("4111111111111111")
  )

}
