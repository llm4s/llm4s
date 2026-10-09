package org.llm4s.agent.guardrails.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Scenarios for [[RegexValidator]] listed in issue #941, alongside the pattern, factory and preset
 * coverage in `SimpleValidatorSpec`: custom patterns, regex metacharacters in the input, an invalid
 * pattern, multi-line input, empty input, and the default error message of the string factory.
 */
class RegexValidatorSpec extends AnyFlatSpec with Matchers {

  "RegexValidator" should "match anywhere in the text and name the pattern when it does not match" in {
    val validator = RegexValidator("hello")

    validator.validate("hello world") shouldBe Right("hello world")

    val result = validator.validate("goodbye world")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Value does not match pattern: hello")
  }

  // -- Custom patterns (the presets are covered in SimpleValidatorSpec) --

  it should "validate an email-like pattern" in {
    val validator = RegexValidator("^[\\w.]+@[\\w.]+$")

    validator.validate("user@example.com") shouldBe Right("user@example.com")
    validator.validate("not-an-email").isLeft shouldBe true
  }

  it should "validate a phone number pattern" in {
    val validator = RegexValidator("^\\+?[0-9]{10,15}$")

    validator.validate("+919876543210") shouldBe Right("+919876543210")
    validator.validate("invalid-phone").isLeft shouldBe true
  }

  it should "treat regex metacharacters in the input as plain text" in {
    // The input is only searched, never compiled, so `. * [ ] ( ) ? +` in it cannot break matching
    val text = "text with . * [ ] ( ) ? + characters"

    RegexValidator("\\* \\[ \\]").validate(text) shouldBe Right(text)
    RegexValidator("^[a-z .*\\[\\]()?+]+$").validate(text) shouldBe Right(text)
  }

  it should "return Left, without throwing, for a syntactically invalid pattern" in {
    val result = RegexValidator("[broken").validate("anything")

    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Invalid or unsafe regex pattern")
  }

  // -- Multi-line input --

  it should "match across lines when the pattern enables DOTALL" in {
    val validator = RegexValidator("(?s).*second line.*")
    val text      = "first line\nsecond line\nthird line"

    validator.validate(text) shouldBe Right(text)
  }

  it should "not let a plain dot cross a line break" in {
    // `.` stops at a line terminator, so this needs (?s) to span the two lines
    RegexValidator("first.*third").validate("first line\nthird line").isLeft shouldBe true
  }

  it should "anchor to the whole text unless MULTILINE is enabled" in {
    val text = "first line\nsecond line"

    RegexValidator("^second line$").validate(text).isLeft shouldBe true
    RegexValidator("(?m)^second line$").validate(text) shouldBe Right(text)
  }

  // -- Empty input --

  it should "accept empty input only when the pattern allows it" in {
    RegexValidator("^$").validate("") shouldBe Right("")
    RegexValidator("^$").validate("not empty").isLeft shouldBe true
    RegexValidator("[0-9]+").validate("").isLeft shouldBe true
  }
}
