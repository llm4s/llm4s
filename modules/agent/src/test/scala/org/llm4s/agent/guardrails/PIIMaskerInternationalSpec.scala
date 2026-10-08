package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.PIIMasker
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * International phone numbers (a leading `+`, 8 to 15 digits) and the 15-digit American Express layout, which
 * [[PIIMaskerFormatsSpec]] and [[PIIMaskerSpec]] leave out.
 *
 * Each expectation is the whole output, so a masker that eats the text around a number, or leaves part of it
 * behind, fails. The inputs that must NOT change are as much the point as the ones that must: the new patterns
 * favour recall, but only inside the shapes the issue names.
 */
class PIIMaskerInternationalSpec extends AnyFlatSpec with Matchers {

  private def phones  = PIIMasker(Seq(PIIType.Phone))
  private def cards   = PIIMasker(Seq(PIIType.CreditCard))
  private def default = PIIMasker()
  private def all     = PIIMasker.all

  // ==========================================================================
  // International phone numbers
  // ==========================================================================

  private def internationalNumbers = Seq(
    "UK"              -> "+44 20 7946 0958",
    "UK with trunk 0" -> "+44 (0) 20 7946 0958",
    "Germany"         -> "+49 30 12345678",
    "Germany, dashes" -> "+49-30-12345678",
    "India"           -> "+91 98765 43210",
    "India, dashes"   -> "+91-98765-43210",
    "Japan"           -> "+81 3-1234-5678",
    "Japan, dots"     -> "+81.3.1234.5678",
    "France"          -> "+33 1 23 45 67 89",
    "No separators"   -> "+4915123456789",
    "Eight digits"    -> "+12345678",
    "Fifteen digits"  -> "+123456789012345",
    "+1, parentheses" -> "+1 (555) 123-4567"
  )

  "PIIMasker's phone masking" should "replace an international number in every format and leave the text around it alone" in {
    internationalNumbers.foreach { case (label, number) =>
      withClue(s"$label '$number': ") {
        phones.transform(s"Call $number now") shouldBe "Call [REDACTED_PHONE] now"
      }
    }
  }

  it should "preserve line breaks between plus-prefixed digits" in {
    Seq("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029").foreach { separator =>
      Seq(8, 10, 15).foreach { digits =>
        val input = (1 to digits).map(_ % 10).mkString("+", separator, "")
        phones.transform(input) shouldBe input
      }
    }
    phones.transform("Call +44\t20\t7946\t0958 now") shouldBe "Call [REDACTED_PHONE] now"
  }

  it should "give the same result from the default masker and from the one with every type" in {
    internationalNumbers.foreach { case (label, number) =>
      withClue(s"$label '$number': ") {
        default.transform(s"Call $number now") shouldBe "Call [REDACTED_PHONE] now"
        all.transform(s"Call $number now") shouldBe "Call [REDACTED_PHONE] now"
      }
    }
  }

  it should "leave no digit of an international number behind" in {
    internationalNumbers.foreach { case (label, number) =>
      withClue(s"$label '$number': ")(phones.transform(s"Call $number now").exists(_.isDigit) shouldBe false)
    }
  }

  it should "keep the punctuation and words next to a number" in {
    phones.transform("(+44 20 7946 0958)") shouldBe "([REDACTED_PHONE])"
    phones.transform("\"+44 20 7946 0958\"") shouldBe "\"[REDACTED_PHONE]\""
    phones.transform("Call +44 20 7946 0958.") shouldBe "Call [REDACTED_PHONE]."
    phones.transform("tel:+442079460958") shouldBe "tel:[REDACTED_PHONE]"
    phones.transform("a\n+44 20 7946 0958\nb") shouldBe "a\n[REDACTED_PHONE]\nb"
  }

  it should "mask several numbers of different kinds in one text" in {
    phones.transform("call 555-123-4567 or +44 20 7946 0958, or +91 98765 43210") shouldBe
      "call [REDACTED_PHONE] or [REDACTED_PHONE], or [REDACTED_PHONE]"
  }

  it should "stop at 15 digits and leave what follows, as an E.164 number has at most 15" in {
    phones.transform("+44 20 7946 0958 1234") shouldBe "[REDACTED_PHONE] 1234"
  }

  it should "treat the digits as one number whether or not a space precedes the plus" in {
    phones.transform("x 5 +12345678 y") shouldBe "x 5 [REDACTED_PHONE] y"
  }

  private def notPhoneNumbers = Seq(
    "a plain 8-digit number"            -> "ref 12345678",
    "a date"                            -> "on 2026-10-07",
    "a version"                         -> "version 1.2.3.4",
    "an IPv4 address"                   -> "host 192.168.1.100",
    "a dotted version of four numbers"  -> "version 10.20.30.40 ok",
    "an ISBN"                           -> "ISBN 9780306406157",
    "an order number"                   -> "order 20261007123456",
    "a plus with only 7 digits"         -> "+1234567",
    "a plus with 17 digits"             -> "+44123456789012345",
    "a plus with a space before digits" -> "a + 12345678 b",
    "a plus between words"              -> "C++ and 2+2"
  )

  it should "not touch text that is not a phone number" in {
    notPhoneNumbers.foreach { case (label, input) =>
      withClue(s"$label '$input': ")(phones.transform(input) shouldBe input)
    }
  }

  it should "not touch a digit run that merely follows a digit and a plus" in {
    phones.transform("1+44 20 7946 0958") shouldBe "1+44 20 7946 0958"
  }

  // ==========================================================================
  // The 15-digit American Express layout
  // ==========================================================================

  private def amexNumbers = Seq(
    "37 plain"      -> "378282246310005",
    "37 spaces"     -> "3782 822463 10005",
    "37 dashes"     -> "3782-822463-10005",
    "34 plain"      -> "340000000000009",
    "34 spaces"     -> "3400 000000 00009",
    "37, bad check" -> "371234567890123"
  )

  "PIIMasker's card masking" should "replace a 15-digit American Express number in every layout" in {
    amexNumbers.foreach { case (label, number) =>
      withClue(s"$label '$number': ") {
        cards.transform(s"Pay with $number ok") shouldBe "Pay with [REDACTED_CARD] ok"
      }
    }
  }

  it should "give the same result from the default masker and from the one with every type" in {
    amexNumbers.foreach { case (label, number) =>
      withClue(s"$label '$number': ") {
        default.transform(s"Pay with $number ok") shouldBe "Pay with [REDACTED_CARD] ok"
        all.transform(s"Pay with $number ok") shouldBe "Pay with [REDACTED_CARD] ok"
      }
    }
  }

  it should "mask a 16-digit and a 15-digit card in one text" in {
    cards.transform("Pay 3782 822463 10005 and 4111 1111 1111 1111") shouldBe "Pay [REDACTED_CARD] and [REDACTED_CARD]"
  }

  private def notCards = Seq(
    "15 digits that do not start like Amex" -> "123456789012345",
    "15 digits with another grouping"       -> "1234 567890 12345",
    "14 digits"                             -> "37828224631000",
    "17 digits"                             -> "37828224631000512",
    "15 digits inside a longer run"         -> "9378282246310005"
  )

  it should "not touch a digit run that is not shaped like a card" in {
    notCards.foreach { case (label, input) =>
      withClue(s"$label '$input': ")(cards.transform(input) shouldBe input)
    }
  }

  // ==========================================================================
  // Other detectors, other presets and overlapping matches
  // ==========================================================================

  "PIIMasker with several detectors" should "mask an international number that runs into an email address as one span" in {
    // the last four digits of the number are also the start of the email's local part
    val out = all.transform("+44 20 7946 0958.john@example.com")
    out shouldBe "[REDACTED_PHONE]"
    (out should not).include("john")
  }

  it should "mask an email whose local part looks like an international number" in {
    all.transform("+44123456789@example.com") shouldBe "[REDACTED_EMAIL]"
  }

  it should "mask an international number that is also a passport-shaped run of digits" in {
    all.transform("+33 123456789") shouldBe "[REDACTED_PHONE]"
    PIIMasker.sensitive.transform("+33 123456789") shouldBe "[REDACTED_PHONE]"
  }

  it should "mask a plain 15-digit card number under the presets that also match bank accounts" in {
    // a card-shaped run is also account-shaped: both match the same text, and the card, listed first, names it
    PIIMasker.sensitive.transform("378282246310005") shouldBe "[REDACTED_CARD]"
    PIIMasker.financial.transform("378282246310005") shouldBe "[REDACTED_CARD]"
  }

  it should "mask a plain 16-digit card number under the presets that also match bank accounts" in {
    PIIMasker.sensitive.transform("4111111111111111") shouldBe "[REDACTED_CARD]"
    PIIMasker.financial.transform("4111111111111111") shouldBe "[REDACTED_CARD]"
  }

  it should "name a number that two detectors match by the type listed first" in {
    all.transform("123456789") shouldBe "[REDACTED_SSN]"
  }

  it should "mask numbers of several kinds in one text" in {
    all.transform("call 555-123-4567 or +44 20 7946 0958, mail a@b.com, card 3782 822463 10005") shouldBe
      "call [REDACTED_PHONE] or [REDACTED_PHONE], mail [REDACTED_EMAIL], card [REDACTED_CARD]"
  }

  // ==========================================================================
  // Masking twice, and large inputs
  // ==========================================================================

  "PIIMasker" should "give the same text when it masks its own output again" in {
    val inputs = internationalNumbers.map(_._2) ++ amexNumbers.map(_._2) ++ Seq(
      "call 555-123-4567 or +44 20 7946 0958, mail a@b.com, card 3782 822463 10005",
      "+44 20 7946 0958.john@example.com"
    )
    inputs.foreach { input =>
      val once = all.transform(input)
      withClue(s"input '$input': ")(all.transform(once) shouldBe once)
    }
  }

  // A pattern that backtracks catastrophically never returns and cannot be interrupted, so each call runs in a
  // future and the test fails at the bound instead of hanging the suite. The bound is generous on purpose: no
  // assertion depends on how fast the masker is, only on it finishing.
  private def finishing[A](work: => A): A = Await.result(Future(work)(ExecutionContext.global), 60.seconds)

  it should "mask every international number in a large input" in {
    val input = "+44 20 7946 0958 " * 5000
    val out   = finishing(all.transform(input))
    "\\[REDACTED_PHONE\\]".r.findAllIn(out).size shouldBe 5000
    out.exists(_.isDigit) shouldBe false
  }

  it should "mask every 15-digit card number in a large input" in {
    val input = "Pay 3782 822463 10005 ok. " * 5000
    val out   = finishing(all.transform(input))
    "\\[REDACTED_CARD\\]".r.findAllIn(out).size shouldBe 5000
    out.exists(_.isDigit) shouldBe false
    out.startsWith("Pay [REDACTED_CARD] ok. Pay [REDACTED_CARD] ok. ") shouldBe true
  }

  it should "leave a plus followed by a very long run of digits as it is" in {
    val input = "+" + ("1234567890" * 10000)
    finishing(phones.transform(input)) shouldBe input
  }

  it should "leave a long run of digits and spaces with no plus as it is" in {
    val input = "4 " * 50000
    finishing(phones.transform(input)) shouldBe input
  }
}
