package org.llm4s.agent.guardrails.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `ToneValidator` on text that spans lines.
 *
 * LLM output is routinely several lines or paragraphs. The keyword checks used `String.matches(".*kw.*")`, and `.`
 * does not match a line break, so a newline anywhere in the text made every check fail and the text came out
 * `Neutral` whatever it said. These specs pin the behaviour for multi-line text, and that single-line text is
 * classified as it always was.
 *
 * `detectTone` is private, so the tone is read the way a caller sees it: a validator that allows no tone refuses
 * every text and names the one it detected.
 */
class ToneValidatorMultiLineSpec extends AnyFlatSpec with Matchers {

  private val Detected = """Output tone \((\w+)\) not allowed""".r

  private def detect(text: String): String = {
    val message = ToneValidator(Set.empty)
      .validate(text)
      .fold(_.message, _ => fail("a validator that allows no tone accepted the text"))
    Detected.findFirstMatchIn(message).map(_.group(1)).getOrElse(fail(s"no detected tone in: $message"))
  }

  private def rejection(validator: ToneValidator, text: String): String =
    validator.validate(text).fold(_.message, _ => fail("the validator accepted the text"))

  // Lines with no tone keyword in them; one of them is replaced by a keyword line in the tests below.
  private val Filler = Seq("The deploy finished", "The notes are attached", "Nothing else changed")

  private def withLineAt(index: Int, line: String): String = Filler.updated(index, line).mkString("\n")

  private val KeywordLines = Seq(
    "Professional" -> "Please review the attached report",
    "Casual"       -> "The results look awesome",
    "Friendly"     -> "Hello from the whole team",
    "Formal"       -> "Furthermore the tests pass"
  )

  "ToneValidator" should "classify multi-line text with no keyword as Neutral" in {
    detect(Filler.mkString("\n")) shouldBe "Neutral"
  }

  for {
    (tone, line) <- KeywordLines
    index        <- 0 to 2
  }
    it should s"see a $tone keyword on line ${index + 1} of 3" in {
      detect(withLineAt(index, line)) shouldBe tone
    }

  it should "not let the line a keyword is on change which tone wins" in {
    // Professional is checked before Casual, and Casual before Friendly, wherever they sit.
    detect("The results look awesome\nNothing else changed\nPlease review the attached report") shouldBe "Professional"
    detect("Please review the attached report\nNothing else changed\nThe results look awesome") shouldBe "Professional"
    detect("Hello from the whole team\nNothing else changed\nThe results look awesome") shouldBe "Casual"
    detect("Furthermore the tests pass\nNothing else changed\nHello from the whole team") shouldBe "Friendly"
  }

  it should "see keywords across paragraphs separated by blank lines" in {
    detect("Thank you for your inquiry.\n\nWe will respond shortly.") shouldBe "Professional"
    detect("The deploy finished.\n\n\n\nHey, the results look cool.") shouldBe "Casual"
  }

  it should "see keywords in Windows (CRLF) and old Mac (CR) line endings" in {
    detect("Thank you for your inquiry.\r\nWe will respond shortly.") shouldBe "Professional"
    detect("The deploy finished\rHello from the whole team") shouldBe "Friendly"
  }

  it should "see keywords across Unicode line and paragraph separators" in {
    detect("The deploy finished Please review the attached report") shouldBe "Professional"
    detect("The deploy finished Furthermore the tests pass") shouldBe "Formal"
    detect("The deploy finished\u0085Hello from the whole team") shouldBe "Friendly"
  }

  it should "still match whole words only across lines" in {
    // "this" and "shipped" contain "hi", "ship" and so on, but never as a whole word.
    detect("This shipped\nNothing else changed") shouldBe "Neutral"
    // "pleased" is not "please".
    detect("We are pleased\nNothing else changed") shouldBe "Neutral"
  }

  it should "let the exclamation rule win over a keyword on another line" in {
    detect("Please review the attached report.\nGreat!") shouldBe "Excited"
    detect("Great!\nPlease review the attached report.") shouldBe "Excited"
    detect("The deploy finished.\nNothing else changed.\nGreat!") shouldBe "Excited"
  }

  it should "not call a long exclamation Excited just because it spans lines" in {
    // One sentence of eight words, broken over two lines: no fragment is under five words.
    detect("Please review the attached report today\nand send it back!") shouldBe "Professional"
  }

  it should "classify single-line text as it always did" in {
    detect("Please review the attached report") shouldBe "Professional"
    detect("Thank you for your inquiry") shouldBe "Professional"
    detect("Hey that is cool") shouldBe "Casual"
    detect("Hello team") shouldBe "Friendly"
    detect("Furthermore the results hold") shouldBe "Formal"
    detect("The tests pass") shouldBe "Neutral"
    detect("Wow!") shouldBe "Excited"
    detect("Please review. Thanks!") shouldBe "Excited"
    detect("") shouldBe "Neutral"
  }

  "ToneValidator.professionalOnly" should "accept a professional text that spans lines, unchanged" in {
    val text = "Thank you for your inquiry.\nWe will respond shortly."

    ToneValidator.professionalOnly.validate(text) shouldBe Right(text)
  }

  it should "accept a multi-paragraph professional text with CRLF line endings, unchanged" in {
    val text = "Dear customer,\r\n\r\nThank you for your inquiry.\r\n\r\nKind regards,\r\nSupport"

    ToneValidator.professionalOnly.validate(text) shouldBe Right(text)
  }

  it should "still refuse a multi-line text of another tone, naming the tone it saw" in {
    val message = rejection(ToneValidator.professionalOnly, "The deploy finished\nThe results look awesome")

    message should include("Output tone (Casual) not allowed")
    message should include("Allowed tones: Professional")
  }

  "ToneValidator.professionalOrFriendly" should "accept a friendly text that spans lines" in {
    val text = "Hello team\nThe deploy finished."

    ToneValidator.professionalOrFriendly.validate(text) shouldBe Right(text)
  }

  "ToneValidator.casualOrFriendly" should "refuse a professional text that spans lines, not call it Neutral" in {
    val message = rejection(ToneValidator.casualOrFriendly, "Thank you for your inquiry.\nWe will respond shortly.")

    message should include("Output tone (Professional) not allowed")
  }

  "A Friendly-only validator" should "accept a multi-line friendly text" in {
    val text = "Hello from the whole team\nNothing else changed"

    ToneValidator(Set(Tone.Friendly)).validate(text) shouldBe Right(text)
  }

  "ToneValidator.allowAll" should "accept multi-line text of every tone" in {
    KeywordLines.foreach { case (_, line) =>
      val text = withLineAt(1, line)
      ToneValidator.allowAll.validate(text) shouldBe Right(text)
    }
  }
}
