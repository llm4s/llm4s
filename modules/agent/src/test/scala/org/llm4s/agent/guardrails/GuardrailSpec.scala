package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin._
import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuardrailSpec extends AnyFlatSpec with Matchers {

  "LengthCheck" should "accept input within bounds" in {
    val guardrail = new LengthCheck(min = 5, max = 20)
    val result    = guardrail.validate("Valid input")

    result shouldBe Right("Valid input")
  }

  it should "reject input that is too short" in {
    val guardrail = new LengthCheck(min = 10, max = 100)
    val result    = guardrail.validate("Short")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject input that is too long" in {
    val guardrail = new LengthCheck(min = 1, max = 10)
    val result    = guardrail.validate("This is way too long for the maximum")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "accept input at exact minimum" in {
    val guardrail = new LengthCheck(min = 5, max = 20)
    val result    = guardrail.validate("12345")

    result shouldBe Right("12345")
  }

  it should "accept input at exact maximum" in {
    val guardrail = new LengthCheck(min = 5, max = 10)
    val result    = guardrail.validate("1234567890")

    result shouldBe Right("1234567890")
  }

  "LengthCheck.maxOnly" should "only check maximum length" in {
    val guardrail = LengthCheck.maxOnly(10)

    guardrail.validate("") shouldBe Right("")
    guardrail.validate("1234567890") shouldBe Right("1234567890")
    guardrail.validate("12345678901").isLeft shouldBe true
  }

  "LengthCheck.minOnly" should "only check minimum length" in {
    val guardrail = LengthCheck.minOnly(5)

    guardrail.validate("1234").isLeft shouldBe true
    guardrail.validate("12345") shouldBe Right("12345")
    guardrail.validate("123456789012345") shouldBe Right("123456789012345")
  }

  "ProfanityFilter" should "accept clean content" in {
    val guardrail = new ProfanityFilter()
    val result    = guardrail.validate("This is clean content")

    result shouldBe Right("This is clean content")
  }

  it should "reject inappropriate content" in {
    val guardrail = new ProfanityFilter()
    val result    = guardrail.validate("This contains badword")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "be case-insensitive by default" in {
    val guardrail = new ProfanityFilter()
    val result    = guardrail.validate("This contains BADWORD")

    result.isLeft shouldBe true
  }

  it should "support custom bad words" in {
    val guardrail = new ProfanityFilter(customBadWords = Set("custom"))
    val result    = guardrail.validate("This contains custom")

    result.isLeft shouldBe true
  }

  it should "be case-sensitive when configured" in {
    val guardrail = ProfanityFilter.caseSensitive(Set("CustomBad"))

    guardrail.validate("This contains custombad") shouldBe Right("This contains custombad")
    guardrail.validate("This contains CustomBad").isLeft shouldBe true
  }

  "JSONValidator" should "accept valid JSON" in {
    val guardrail = new JSONValidator()
    val result    = guardrail.validate("""{"key": "value"}""")

    result shouldBe Right("""{"key": "value"}""")
  }

  it should "accept valid JSON array" in {
    val guardrail = new JSONValidator()
    val result    = guardrail.validate("""[1, 2, 3]""")

    result shouldBe Right("""[1, 2, 3]""")
  }

  it should "reject invalid JSON" in {
    val guardrail = new JSONValidator()
    val result    = guardrail.validate("not json")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "reject malformed JSON" in {
    val guardrail = new JSONValidator()
    val result    = guardrail.validate("""{"key": "value"  missing brace""")

    result.isLeft shouldBe true
  }

  "RegexValidator" should "accept matching content" in {
    val guardrail = new RegexValidator("^[0-9]+$".r)
    val result    = guardrail.validate("12345")

    result shouldBe Right("12345")
  }

  it should "reject non-matching content" in {
    val guardrail = new RegexValidator("^[0-9]+$".r)
    val result    = guardrail.validate("abc123")

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  it should "support custom error messages" in {
    val guardrail = new RegexValidator("^[0-9]+$".r, Some("Must be numeric"))
    val result    = guardrail.validate("abc")

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Must be numeric")
  }

  "RegexValidator.email" should "validate email addresses" in {
    val guardrail = RegexValidator.email

    guardrail.validate("test@example.com") shouldBe Right("test@example.com")
    guardrail.validate("invalid").isLeft shouldBe true
    guardrail.validate("@example.com").isLeft shouldBe true
  }

  "RegexValidator.alphanumeric" should "accept only alphanumeric" in {
    val guardrail = RegexValidator.alphanumeric

    guardrail.validate("abc123") shouldBe Right("abc123")
    guardrail.validate("abc-123").isLeft shouldBe true
  }

  "ToneValidator" should "accept allowed tones" in {
    val guardrail = new ToneValidator(Set(Tone.Professional, Tone.Friendly))
    // Note: Detection is basic, so we use keywords that trigger the tone
    val result = guardrail.validate("Thank you for your assistance")

    result shouldBe Right("Thank you for your assistance")
  }

  it should "reject disallowed tones" in {
    val guardrail = new ToneValidator(Set(Tone.Professional))
    // Using casual language that should be detected
    val result = guardrail.validate("Hey that's awesome!")

    // Note: Due to basic detection, this test might be flaky
    // In production, use better tone detection
    result.isLeft shouldBe true
  }

  "ToneValidator.professionalOnly" should "only allow professional tone" in {
    val guardrail = ToneValidator.professionalOnly

    guardrail.validate("Please kindly review") shouldBe Right("Please kindly review")
  }

  "CompositeGuardrail.all" should "pass if all guardrails pass" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 100),
        new ProfanityFilter()
      )
    )

    val result = composite.validate("Hello, world!")
    result shouldBe Right("Hello, world!")
  }

  it should "fail if any guardrail fails" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 10), // Will fail
        new ProfanityFilter()
      )
    )

    val result = composite.validate("This is too long for the limit")
    result.isLeft shouldBe true
  }

  it should "aggregate all errors" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(100, 200),                        // Will fail (too short)
        new ProfanityFilter(customBadWords = Set("test")) // Will also fail
      )
    )

    val result = composite.validate("test")
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
  }

  "CompositeGuardrail.any" should "pass if any guardrail passes" in {
    val composite = CompositeGuardrail.any(
      Seq(
        new RegexValidator("^[0-9]+$".r), // Will fail
        new RegexValidator("^[a-z]+$".r)  // Will pass
      )
    )

    val result = composite.validate("abc")
    result shouldBe Right("abc")
  }

  it should "fail if all guardrails fail" in {
    val composite = CompositeGuardrail.any(
      Seq(
        new RegexValidator("^[0-9]+$".r),
        new RegexValidator("^[A-Z]+$".r)
      )
    )

    val result = composite.validate("abc123")
    result.isLeft shouldBe true
  }

  "CompositeGuardrail.sequential" should "run guardrails in sequence" in {
    val composite = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(1, 100),
        new ProfanityFilter()
      )
    )

    composite.validate("Clean text") shouldBe Right("Clean text")
  }

  it should "short-circuit on first failure" in {
    val composite = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(100, 200), // Will fail immediately
        new ProfanityFilter()      // Should not be evaluated
      )
    )

    val result = composite.validate("Short")
    result.isLeft shouldBe true
  }

  "Guardrail.andThen" should "compose guardrails" in {
    val lengthCheck     = new LengthCheck(1, 100)
    val profanityFilter = new ProfanityFilter()
    val composed        = lengthCheck.andThen(profanityFilter)

    composed.validate("Clean text") shouldBe Right("Clean text")
    composed.validate("badword").isLeft shouldBe true
  }
}
