package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random
import scala.util.matching.Regex

/**
 * Pins the matches of the PII patterns at the edges #1713 touched: the Email pattern now starts a match attempt only
 * at the start of a run of local-part characters or where the previous match ended, the SSN pattern no longer joins
 * digit groups across a line break, and the Phone pattern does not read a `UTC`/`GMT` offset as an international
 * number.
 */
class PIIPatternsEdgeCasesSpec extends AnyFlatSpec with Matchers {

  // The patterns as they were before #1713, the reference for "nothing else changed".
  private val emailBefore: Regex = """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""".r
  private val ssnBefore: Regex   = """(?<!\d)(?!000|666|9\d{2})\d{3}[-\s]?(?!00)\d{2}[-\s]?(?!0000)\d{4}(?!\d)""".r

  // Every line break Java's `\R` matches.
  private val lineBreaks = Seq(
    "LF"     -> "\n",
    "CR"     -> "\r",
    "CRLF"   -> "\r\n",
    "NEL"    -> "\u0085",
    "U+2028" -> "\u2028",
    "U+2029" -> "\u2029",
    "VT"     -> "\u000B",
    "FF"     -> "\f"
  )

  private def emails(text: String): Seq[String] = PIIType.Email.findAll(text).map(_.value)
  private def ssns(text: String): Seq[String]   = PIIType.SSN.findAll(text).map(_.value)
  private def phones(text: String): Seq[String] = PIIType.Phone.findAll(text).map(_.value)

  private def spans(pattern: Regex, text: String): List[(Int, Int)] =
    pattern.findAllMatchIn(text).map(m => (m.start, m.end)).toList

  // ==========================================================================
  // Email: back-to-back and adjacent addresses
  // ==========================================================================

  "Email pattern" should "find an address that starts where the previous one ended" in {
    // A start at a run start alone would lose the second address here: the `_`, `+`, `.`, `-` and `%` that begin it
    // follow a letter of the first one. The previous match's end (`\G`) keeps it.
    emails("a@b.com_x@y.org") shouldBe Seq("a@b.com", "_x@y.org")
    emails("a@b.com+c@d.org") shouldBe Seq("a@b.com", "+c@d.org")
    emails("a@b.com.c@d.org") shouldBe Seq("a@b.com", ".c@d.org")
    emails("a@b.com-c@d.org") shouldBe Seq("a@b.com", "-c@d.org")
    emails("a@b.io%c@d.io") shouldBe Seq("a@b.io", "%c@d.io")
  }

  it should "find an address that starts inside the top-level domain the previous one stopped short of" in {
    emails("a@b.co1@x.io") shouldBe Seq("a@b.co", "1@x.io")
  }

  it should "keep a top-level domain that runs into the next address whole" in {
    emails("x@y.comz@w.io") shouldBe Seq("x@y.comz")
  }

  it should "start a local part after a second @" in {
    emails("a@b@c.com") shouldBe Seq("b@c.com")
    emails("a@b.c.d@e.fg") shouldBe Seq("b.c.d@e.fg")
  }

  it should "find addresses separated by punctuation or whitespace" in {
    emails("a@b.io,c@d.io") shouldBe Seq("a@b.io", "c@d.io")
    emails("a@b.io;c@d.io c@e.io\nf@g.io") shouldBe Seq("a@b.io", "c@d.io", "c@e.io", "f@g.io")
    emails("<a@b.io><c@d.io>") shouldBe Seq("a@b.io", "c@d.io")
  }

  // ==========================================================================
  // Email: context around one address
  // ==========================================================================

  it should "find an address after punctuation" in {
    emails("(a@b.io)") shouldBe Seq("a@b.io")
    emails("email:a@b.io!") shouldBe Seq("a@b.io")
    emails("see [a@b.io]") shouldBe Seq("a@b.io")
  }

  it should "find the address of a mailto link" in {
    emails("<a href=\"mailto:alice@example.org\">mail</a>") shouldBe Seq("alice@example.org")
    emails("mailto:alice@example.org") shouldBe Seq("alice@example.org")
  }

  it should "find a quoted address" in {
    emails("\"bob@example.net\"") shouldBe Seq("bob@example.net")
    emails("'bob@example.net'") shouldBe Seq("bob@example.net")
    emails("“bob@example.net”") shouldBe Seq("bob@example.net")
  }

  it should "keep a subaddress in the local part" in {
    emails("a+tag@x.io") shouldBe Seq("a+tag@x.io")
    emails("write to first.last+news@mail.example.co.uk today") shouldBe Seq("first.last+news@mail.example.co.uk")
  }

  it should "mask back-to-back addresses completely" in {
    PIIPatterns.maskAll("a@b.com_x@y.org", Seq(PIIType.Email)) shouldBe "[REDACTED_EMAIL][REDACTED_EMAIL]"
  }

  it should "match exactly what the pattern before #1713 matched" in {
    val corpus = Seq(
      "Contact john.doe@example.com or jane_smith+news@mail.example.co.uk today.",
      "first@one.io,second@two.io;third@three.io",
      "user%name@host.museum.",
      "aaaa@bbbb.cc.dd@ee.ff",
      "-@-.aa ..@..aa a@.aa a@a..aa",
      "x@y.comz@w.io a@b.co1@x.io a@b@c.com"
    )
    val alphabet = "aZx09._%+-@@ ,;:<>\"'()\n".toVector
    val random   = new Random(1713)
    val generated = Seq.fill(20000)(
      Seq.fill(random.nextInt(40))(alphabet(random.nextInt(alphabet.size))).mkString
    )
    (corpus ++ generated).foreach(text => spans(PIIType.Email.pattern, text) shouldBe spans(emailBefore, text))
  }

  // ==========================================================================
  // SSN: groups stay on one line
  // ==========================================================================

  lineBreaks.foreach { case (name, break) =>
    "SSN pattern" should s"not join digit groups across a $name" in {
      ssns(s"123${break}45${break}6789") shouldBe empty
      ssns(s"123-45${break}6789") shouldBe empty
      ssns(s"123${break}45-6789") shouldBe empty
      PIIPatterns.maskAll(s"123${break}45${break}6789", Seq(PIIType.SSN)) shouldBe s"123${break}45${break}6789"
    }

    it should s"still find a one-line SSN next to a $name" in {
      ssns(s"before${break}123-45-6789${break}after") shouldBe Seq("123-45-6789")
    }
  }

  it should "join digit groups across horizontal whitespace" in {
    ssns("123 45 6789") shouldBe Seq("123 45 6789")
    ssns("123\t45\t6789") shouldBe Seq("123\t45\t6789")
    ssns("123 45 6789") shouldBe Seq("123 45 6789")
    ssns("123 45-6789") shouldBe Seq("123 45-6789")
  }

  it should "match what the pattern before #1713 matched when no line break or Unicode space is involved" in {
    val alphabet = "0123456789-- \t,a.\u0085\u2028".toVector
    val random   = new Random(1713)
    (1 to 20000).foreach { _ =>
      val text = Seq.fill(random.nextInt(30))(alphabet(random.nextInt(alphabet.size))).mkString
      spans(PIIType.SSN.pattern, text) shouldBe spans(ssnBefore, text)
    }
  }

  // ==========================================================================
  // Phone: time-zone offsets and numbers longer than E.164
  // ==========================================================================

  "Phone pattern" should "not read a UTC or GMT offset and the date after it as an international number" in {
    phones("UTC+5 2026-10-09 12:30") shouldBe empty
    phones("GMT+1 2026-10-09 12:30") shouldBe empty
    phones("utc +5 2026-10-09 12:30") shouldBe empty
    phones("GMT\t+12 34 5678 9012") shouldBe empty
    PIIPatterns.maskAll("UTC+5 2026-10-09 12:30") shouldBe "UTC+5 2026-10-09 12:30"
  }

  it should "still find a number after a word that is not a time zone" in {
    phones("Phone+44 20 7946 0958") shouldBe Seq("+44 20 7946 0958")
    phones("tel:+44 20 7946 0958") shouldBe Seq("+44 20 7946 0958")
    PIIPatterns.maskAll("a@b.com+44 20 7946 0958", Seq(PIIType.Email, PIIType.Phone)) shouldBe
      "[REDACTED_EMAIL][REDACTED_PHONE]"
  }

  it should "still find a US number written after a time zone" in {
    phones("UTC+1 555 123 4567") shouldBe Seq("+1 555 123 4567")
  }

  it should "mask at most 15 digits of a separated number and leave the rest, as its Scaladoc says" in {
    phones("+44 20 7946 0958 1234 5678") shouldBe Seq("+44 20 7946 0958")
    phones("+44123456789012345") shouldBe empty
  }
}
