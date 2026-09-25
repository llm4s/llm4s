package org.llm4s.agent.guardrails.builtin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RegexValidatorSpec extends AnyFlatSpec with Matchers {

  "RegexValidator" should "pass when text matches the configured pattern" in {
    val validator = RegexValidator("hello")

    validator.validate("hello world") shouldBe Right("hello world")
  }

  it should "fail when text does not match the configured pattern" in {
    val validator = RegexValidator("hello")

    val result = validator.validate("goodbye world")

    result.isLeft shouldBe true
    result.left.toOption.get.toString should include("Value does not match pattern")
  }

  it should "validate email patterns" in {
    val validator = RegexValidator("^[\\w.]+@[\\w.]+$")

    validator.validate("user@example.com").isRight shouldBe true
    validator.validate("not-an-email").isLeft shouldBe true
  }

  it should "validate phone number patterns" in {
    val validator = RegexValidator("^\\+?[0-9]{10,15}$")

    validator.validate("+919876543210").isRight shouldBe true
    validator.validate("invalid-phone").isLeft shouldBe true
  }

  it should "handle multiline text" in {
    val validator = RegexValidator("(?s).*second line.*")

    validator.validate("first line\nsecond line\nthird line").isRight shouldBe true
  }

  it should "handle special regex characters in input" in {
    val validator = RegexValidator(".*")

    validator.validate("text with . * [ ] ( ) ? + characters").isRight shouldBe true
  }

  it should "return Left for invalid regex patterns without throwing" in {
    val validator = RegexValidator("[broken")

    noException should be thrownBy validator.validate("anything")

    val result = validator.validate("anything")

    result.isLeft shouldBe true
    result.left.toOption.get.toString should include("Invalid or unsafe regex pattern")
  }

  it should "handle empty string input consistently" in {
    val validator = RegexValidator("^$")

    validator.validate("") shouldBe Right("")
    validator.validate("not empty").isLeft shouldBe true
  }
}