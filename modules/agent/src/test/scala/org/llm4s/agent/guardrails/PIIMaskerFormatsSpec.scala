package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.PIIMasker
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * What [[PIIMaskerSpec]] does not pin down: the formats a phone number or card number is written in, PII at the
 * very start and end of the input and next to punctuation, and a large input.
 *
 * Each expectation is the whole output, not a fragment, so a masker that eats the text around a match, or leaves
 * part of the value behind, fails here. The expected values were taken from running the masker, not from the
 * patterns' comments.
 */
class PIIMaskerFormatsSpec extends AnyFlatSpec with Matchers {

  private def phones = PIIMasker(Seq(PIIType.Phone))
  private def cards  = PIIMasker(Seq(PIIType.CreditCard))
  private def all    = PIIMasker.all

  // ==========================================================================
  // Phone numbers: the formats the detector documents
  // ==========================================================================

  private def usPhoneFormats = Seq(
    "(555) 123-4567",
    "(555)123-4567",
    "555-123-4567",
    "555.123.4567",
    "555 123 4567",
    "5551234567",
    "+1 555 123 4567",
    "+1-555-123-4567",
    "1-555-123-4567"
  )

  "PIIMasker's phone masking" should "replace a US number in every supported format and leave the text around it alone" in {
    usPhoneFormats.foreach { number =>
      withClue(s"format '$number': ") {
        phones.transform(s"Call $number now") shouldBe "Call [REDACTED_PHONE] now"
      }
    }
  }

  it should "leave no digit of the number in the output" in {
    usPhoneFormats.foreach { number =>
      val digits = number.filter(_.isDigit)
      withClue(s"format '$number': ") {
        phones.transform(s"Call $number now").exists(_.isDigit) shouldBe false
        // the digits that identify the subscriber are gone, whichever separators were used
        (phones.transform(s"Call $number now") should not).include(digits.takeRight(7))
      }
    }
  }

  it should "mask each of two numbers written in different formats" in {
    phones.transform("Office (555) 123-4567, mobile +1 555 987 6543.") shouldBe
      "Office [REDACTED_PHONE], mobile [REDACTED_PHONE]."
  }

  it should "mask a phone number written in an international format" in
    pendingUntilFixed {
      phones.transform("Call +44 20 7946 0958 now") shouldBe "Call [REDACTED_PHONE] now"
    }

  // ==========================================================================
  // Card numbers: the formats the detector documents
  // ==========================================================================

  private def sixteenDigitCards = Seq(
    "Visa plain"         -> "4111111111111111",
    "Visa spaces"        -> "4111 1111 1111 1111",
    "Visa dashes"        -> "4111-1111-1111-1111",
    "MasterCard spaces"  -> "5500 0000 0000 0004",
    "Discover spaces"    -> "6011 0000 0000 0004",
    "Visa, bad checksum" -> "4111111111111112"
  )

  "PIIMasker's card masking" should "replace a 16-digit card number in every supported format" in {
    sixteenDigitCards.foreach { case (label, number) =>
      withClue(s"$label '$number': ") {
        cards.transform(s"Pay with $number ok") shouldBe "Pay with [REDACTED_CARD] ok"
      }
    }
  }

  it should "mask a card-shaped number without validating its check digit (the patterns favour recall)" in {
    // 4111111111111112 fails the Luhn check, and is masked all the same: documented as recall over precision
    cards.transform("4111111111111112") shouldBe "[REDACTED_CARD]"
  }

  it should "not touch a 16-digit number that does not start like a card brand" in {
    cards.transform("Ref 1234 5678 9012 3456 end") shouldBe "Ref 1234 5678 9012 3456 end"
  }

  it should "mask a 15-digit card number" in
    pendingUntilFixed {
      cards.transform("Pay with 3782 822463 10005 ok") shouldBe "Pay with [REDACTED_CARD] ok"
    }

  // ==========================================================================
  // Position: the start, the end and the whole of the input, and what is next to a match
  // ==========================================================================

  private def atStart = Seq(
    "email" -> ("john@example.com wrote this", "[REDACTED_EMAIL] wrote this"),
    "SSN"   -> ("123-45-6789 is my number", "[REDACTED_SSN] is my number"),
    "phone" -> ("(555) 123-4567 call", "[REDACTED_PHONE] call")
  )

  private def atEnd = Seq(
    "email" -> ("write to john@example.com", "write to [REDACTED_EMAIL]"),
    "SSN"   -> ("my number is 123-45-6789", "my number is [REDACTED_SSN]"),
    "phone" -> ("call (555) 123-4567", "call [REDACTED_PHONE]")
  )

  private def wholeInput = Seq(
    "email" -> ("john@example.com", "[REDACTED_EMAIL]"),
    "SSN"   -> ("123-45-6789", "[REDACTED_SSN]"),
    "phone" -> ("(555) 123-4567", "[REDACTED_PHONE]"),
    "card"  -> ("4111-1111-1111-1111", "[REDACTED_CARD]")
  )

  "PIIMasker at the edges of the input" should "mask PII that starts the input" in {
    atStart.foreach { case (label, (input, expected)) =>
      withClue(s"$label at the start: ")(all.transform(input) shouldBe expected)
    }
  }

  it should "mask PII that ends the input" in {
    atEnd.foreach { case (label, (input, expected)) =>
      withClue(s"$label at the end: ")(all.transform(input) shouldBe expected)
    }
  }

  it should "mask an input that is nothing but the PII" in {
    wholeInput.foreach { case (label, (input, expected)) =>
      withClue(s"$label alone: ")(all.transform(input) shouldBe expected)
    }
  }

  it should "keep the punctuation next to a match" in {
    all.transform("mail john@example.com.") shouldBe "mail [REDACTED_EMAIL]."
    all.transform("\"john@example.com\"") shouldBe "\"[REDACTED_EMAIL]\""
    all.transform("(SSN 123-45-6789)") shouldBe "(SSN [REDACTED_SSN])"
  }

  it should "keep the line structure around a match" in {
    all.transform("a\n123-45-6789\nb") shouldBe "a\n[REDACTED_SSN]\nb"
  }

  it should "mask two matches separated by a single space" in {
    all.transform("a@b.com b@c.com") shouldBe "[REDACTED_EMAIL] [REDACTED_EMAIL]"
  }

  it should "contain none of the original values in the output, wherever they sat in the input" in {
    val originals = Seq("john@example.com", "123-45-6789", "(555) 123-4567", "4111-1111-1111-1111")
    val inputs    = originals.flatMap(o => Seq(o, s"$o tail", s"head $o", s"head $o tail"))
    inputs.foreach { input =>
      val out = all.transform(input)
      originals.foreach(o => withClue(s"input '$input': ")((out should not).include(o)))
    }
  }

  // ==========================================================================
  // A large input
  // ==========================================================================

  "PIIMasker on a large input" should "mask every match, and finish well inside a generous bound" in {
    val filler = "lorem ipsum dolor sit amet " * 1000
    val block  = filler + "john@example.com 123-45-6789 "
    val input  = block * 20 // about 540,000 characters, 40 matches

    // A regex that backtracks catastrophically never returns and cannot be interrupted, so the call runs in a
    // future and the test fails at the bound instead of hanging the suite.
    val started = System.nanoTime()
    val out     = Await.result(Future(all.transform(input))(ExecutionContext.global), 60.seconds)
    val elapsed = (System.nanoTime() - started) / 1000000L

    withClue(s"took $elapsed ms: ") {
      elapsed should be < 30000L
    }
    (out should not).include("john@example.com")
    (out should not).include("123-45-6789")
    "\\[REDACTED_EMAIL\\]".r.findAllIn(out).size shouldBe 20
    "\\[REDACTED_SSN\\]".r.findAllIn(out).size shouldBe 20
    out.length shouldBe input.length - 20 * ("john@example.com".length + "123-45-6789".length) +
      20 * ("[REDACTED_EMAIL]".length + "[REDACTED_SSN]".length)
  }
}
