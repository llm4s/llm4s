package org.llm4s.agent.guardrails.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.util.Using

/**
 * The two normalisation functions the keyword and pattern guardrails match through, tested as functions.
 * Non-ASCII characters are written as unicode escapes so that they are visible in the source.
 */
class MatchTextSpec extends AnyFlatSpec with Matchers {

  private val fullwidthIgnore = "\uFF29\uFF27\uFF2E\uFF2F\uFF32\uFF25"
  private val dottedCapitalI  = "\u0130GNORE"
  private val dotlessI        = "\u0131gnore"
  private val zeroWidthSpace  = "ig\u200Bnore"

  private def withDefaultLocale[A](locale: Locale)(body: => A): A = {
    val previous = Locale.getDefault
    Using.resource(new AutoCloseable { override def close(): Unit = Locale.setDefault(previous) }) { _ =>
      Locale.setDefault(locale)
      body
    }
  }

  "MatchText.canonical" should "turn fullwidth letters into ASCII" in {
    MatchText.canonical(fullwidthIgnore) shouldBe "IGNORE"
  }

  it should "expand a ligature" in {
    MatchText.canonical("\uFB01") shouldBe "fi"
  }

  it should "turn a non-breaking space into an ASCII space" in {
    MatchText.canonical("a\u00A0b") shouldBe "a b"
  }

  it should "remove accents" in {
    MatchText.canonical("\u00EFgn\u00F6re") shouldBe "ignore"
  }

  it should "keep the case" in {
    MatchText.canonical("Hello") shouldBe "Hello"
  }

  it should "keep the Turkish dotless i" in {
    MatchText.canonical("\u0131") shouldBe "\u0131"
  }

  it should "keep a look-alike letter from another script" in {
    MatchText.canonical("\u0456gnore") shouldBe "\u0456gnore"
  }

  it should "remove zero-width space, zero-width joiner, word joiner and byte-order mark" in {
    MatchText.canonical(zeroWidthSpace) shouldBe "ignore"
    MatchText.canonical("ig\u200Dnore") shouldBe "ignore"
    MatchText.canonical("ig\u2060nore") shouldBe "ignore"
    MatchText.canonical("ig\uFEFFnore") shouldBe "ignore"
  }

  it should "return the empty string for the empty string" in {
    MatchText.canonical("") shouldBe ""
  }

  it should "leave plain ASCII text unchanged" in {
    MatchText.canonical("Plain ASCII text, 123!") shouldBe "Plain ASCII text, 123!"
  }

  "MatchText.folded" should "fold spelling variants of a word to the same lower-case text" in {
    MatchText.folded(dottedCapitalI) shouldBe "ignore"
    MatchText.folded(dotlessI) shouldBe "ignore"
    MatchText.folded(fullwidthIgnore) shouldBe "ignore"
    MatchText.folded(zeroWidthSpace) shouldBe "ignore"
  }

  it should "lower-case with Locale.ROOT whatever the default locale is" in {
    val folded = withDefaultLocale(Locale.forLanguageTag("tr-TR"))(MatchText.folded("TITLE"))

    folded shouldBe "title"
  }

  it should "keep a look-alike letter from another script" in {
    MatchText.folded("\u0456gnore") shouldBe "\u0456gnore"
  }

  it should "return the empty string for the empty string" in {
    MatchText.folded("") shouldBe ""
  }

  it should "lower-case plain ASCII text" in {
    MatchText.folded("Plain ASCII Text, 123!") shouldBe "plain ascii text, 123!"
  }

  it should "be idempotent on the samples above" in {
    val samples = Seq(
      dottedCapitalI,
      dotlessI,
      fullwidthIgnore,
      zeroWidthSpace,
      "\uFB01",
      "a\u00A0b",
      "\u00EFgn\u00F6re",
      "Hello",
      "\u0456gnore",
      "TITLE",
      ""
    )

    samples.foreach(sample => MatchText.folded(MatchText.folded(sample)) shouldBe MatchText.folded(sample))
  }
}
