package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.CompositeGuardrail
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// scalastyle:off line.size.limit
class ToneValidatorSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // 1. Professional tone passes professional validator
  // ==========================================================================

  "ToneValidator" should "accept professional tone with business language" in {
    val validator = ToneValidator.professionalOnly
    val text      = "Thank you for your inquiry. We will respond shortly."
    validator.validate(text) shouldBe Right(text)
  }

  it should "accept professional tone from 'please'" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val text      = "Please review the attached proposal at your earliest convenience."
    validator.validate(text) shouldBe Right(text)
  }

  it should "accept professional tone from 'sincerely'" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val text      = "Sincerely appreciate your prompt response on this matter"
    validator.validate(text) shouldBe Right(text)
  }

  it should "accept professional tone from 'kindly'" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val text      = "Kindly forward the document to the team"
    validator.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // 2. Aggressive / ALL CAPS tone is rejected
  // ==========================================================================

  it should "reject ALL CAPS exclamation text as excited tone when professional is required" in {
    val validator = ToneValidator.professionalOnly
    val result    = validator.validate("THIS IS COMPLETELY UNACCEPTABLE!")
    result.isLeft shouldBe true
  }

  it should "reject aggressive short exclamatory phrases" in {
    val validator = new ToneValidator(Set(Tone.Professional, Tone.Neutral))
    val result    = validator.validate("STOP! NOW!")
    result.isLeft shouldBe true
  }

  it should "reject rude phrasing with exclamation marks" in {
    val validator = new ToneValidator(Set(Tone.Professional, Tone.Friendly))
    val result    = validator.validate("No! Bad!")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // 3. Friendly tone passes friendly validator
  // ==========================================================================

  it should "accept friendly tone with warm casual language" in {
    val validator = new ToneValidator(Set(Tone.Friendly))
    val text      = "Hello and thanks for reaching out to us"
    validator.validate(text) shouldBe Right(text)
  }

  it should "accept friendly tone from 'hi'" in {
    val validator = new ToneValidator(Set(Tone.Friendly))
    val text      = "Hi there, we appreciate your feedback"
    validator.validate(text) shouldBe Right(text)
  }

  it should "accept friendly tone from 'appreciate'" in {
    val validator = new ToneValidator(Set(Tone.Friendly))
    val text      = "We really appreciate your patience on this"
    validator.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // 4. Neutral factual text passes most tone validators
  // ==========================================================================

  it should "pass neutral factual text through a neutral-allowing validator" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    val text      = "The temperature is 72 degrees Fahrenheit."
    validator.validate(text) shouldBe Right(text)
  }

  it should "pass neutral factual text through an allowAll validator" in {
    val validator = ToneValidator.allowAll
    val text      = "Water boils at 100 degrees Celsius at sea level."
    validator.validate(text) shouldBe Right(text)
  }

  it should "reject neutral text when only professional is allowed" in {
    val validator = ToneValidator.professionalOnly
    val result    = validator.validate("The boiling point of water is 100C.")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // 5. Empty string — consistent behavior, no exception
  // ==========================================================================

  it should "handle empty string without throwing an exception" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    noException should be thrownBy validator.validate("")
  }

  it should "detect empty string as neutral tone" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    validator.validate("") shouldBe Right("")
  }

  it should "reject empty string when neutral is not allowed" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val result    = validator.validate("")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // 6. Single punctuation string "!!!" — document expectation
  // ==========================================================================

  // "!!!" is detected as Neutral, NOT Excited.
  // Reason: detectTone splits on [.!?] — "!!!" produces an empty array (all chars
  // are delimiters with no content between them), so the "short sentence with !"
  // check finds no segments to evaluate and the text falls through to Neutral.
  it should "detect '!!!' as neutral tone because splitting on delimiters yields no segments" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    validator.validate("!!!") shouldBe Right("!!!")
  }

  it should "reject '!!!' when neutral is not allowed" in {
    val validator = ToneValidator.professionalOnly
    val result    = validator.validate("!!!")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Neutral")
  }

  // ==========================================================================
  // 7. Error message quality — Left result explains expected vs detected
  // ==========================================================================

  it should "include detected tone name in error message" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val result    = validator.validate("Hey that's pretty cool")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Casual")
  }

  it should "include allowed tones in error message" in {
    val validator = new ToneValidator(Set(Tone.Professional, Tone.Formal))
    val result    = validator.validate("Hey that's cool")
    val msg       = result.swap.toOption.get.message
    msg should include("Professional")
    msg should include("Formal")
  }

  it should "include 'not allowed' phrasing in error message" in {
    val validator = new ToneValidator(Set(Tone.Neutral))
    val result    = validator.validate("Hey cool stuff")
    result.swap.toOption.get.message should include("not allowed")
  }

  it should "explain expected vs detected tone in a single message" in {
    val validator = new ToneValidator(Set(Tone.Professional))
    val result    = validator.validate("Furthermore the data is clear")
    val msg       = result.swap.toOption.get.message
    // Error includes detected tone (Formal) and allowed tone (Professional)
    msg should include("Formal")
    msg should include("Professional")
    msg should include("not allowed")
  }

  // ==========================================================================
  // 8. Composition with CompositeGuardrail.all()
  // ==========================================================================

  it should "work inside CompositeGuardrail.all with other validators" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 200),
        new ToneValidator(Set(Tone.Professional)),
      )
    )
    val text = "Please kindly review the attached document"
    composite.validate(text) shouldBe Right(text)
  }

  it should "fail in CompositeGuardrail.all when tone is wrong" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 200),
        new ToneValidator(Set(Tone.Professional)),
      )
    )
    val result = composite.validate("Hey cool stuff")
    result.isLeft shouldBe true
  }

  it should "work in CompositeGuardrail.sequential with other validators" in {
    val composite = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(1, 200),
        new ToneValidator(Set(Tone.Professional, Tone.Friendly)),
      )
    )
    val text = "Thank you for your patience"
    composite.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // Additional: Tone detection — Casual
  // ==========================================================================

  it should "detect casual tone from 'hey'" in {
    val validator = new ToneValidator(Set(Tone.Casual))
    val text      = "Hey what's going on"
    validator.validate(text) shouldBe Right(text)
  }

  it should "detect casual tone from 'awesome'" in {
    val validator = new ToneValidator(Set(Tone.Casual))
    val text      = "That was an awesome presentation"
    validator.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // Additional: Tone detection — Formal
  // ==========================================================================

  it should "detect formal tone from 'furthermore'" in {
    val validator = new ToneValidator(Set(Tone.Formal))
    val text      = "Furthermore the analysis demonstrates significant results"
    validator.validate(text) shouldBe Right(text)
  }

  it should "detect formal tone from 'consequently'" in {
    val validator = new ToneValidator(Set(Tone.Formal))
    val text      = "The experiment consequently proved the hypothesis"
    validator.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // Additional: Tone detection — Excited
  // ==========================================================================

  it should "detect excited tone from exclamation marks with short sentences" in {
    val validator = new ToneValidator(Set(Tone.Excited))
    val text      = "Wow! Great! Amazing!"
    validator.validate(text) shouldBe Right(text)
  }

  // ==========================================================================
  // Additional: Factory method presets
  // ==========================================================================

  it should "allow professional and friendly with professionalOrFriendly preset" in {
    val validator = ToneValidator.professionalOrFriendly
    validator.validate("Thank you kindly").isRight shouldBe true
    validator.validate("Hello and thanks").isRight shouldBe true
  }

  it should "allow casual and friendly with casualOrFriendly preset" in {
    val validator = ToneValidator.casualOrFriendly
    validator.validate("Hey that's cool").isRight shouldBe true
    validator.validate("Hello and thanks").isRight shouldBe true
  }

  it should "allow all tones with allowAll preset" in {
    val validator = ToneValidator.allowAll
    validator.validate("Please kindly review").isRight shouldBe true
    validator.validate("Hey cool stuff").isRight shouldBe true
    validator.validate("Furthermore this is formal").isRight shouldBe true
    validator.validate("Plain factual text").isRight shouldBe true
  }

  // ==========================================================================
  // Additional: Properties — name and description
  // ==========================================================================

  it should "have name 'ToneValidator'" in {
    new ToneValidator(Set(Tone.Neutral)).name shouldBe "ToneValidator"
  }

  it should "list allowed tones in description" in {
    val validator = new ToneValidator(Set(Tone.Professional, Tone.Formal))
    val desc      = validator.description.get
    desc should include("Professional")
    desc should include("Formal")
  }

  it should "include description when constructed via apply" in {
    val validator = ToneValidator(Set(Tone.Casual, Tone.Friendly))
    validator.description shouldBe defined
    validator.description.get should include("Casual")
    validator.description.get should include("Friendly")
  }

  // ==========================================================================
  // Additional: Tone.all coverage
  // ==========================================================================

  "Tone.all" should "contain exactly 6 tones" in {
    Tone.all.size shouldBe 6
  }

  it should "contain all defined tone objects" in {
    Tone.all should contain(Tone.Professional)
    Tone.all should contain(Tone.Casual)
    Tone.all should contain(Tone.Friendly)
    Tone.all should contain(Tone.Formal)
    Tone.all should contain(Tone.Excited)
    Tone.all should contain(Tone.Neutral)
  }

  // ==========================================================================
  // Additional: Tone.name property
  // ==========================================================================

  "Tone name" should "return correct name for each tone" in {
    Tone.Professional.name shouldBe "Professional"
    Tone.Casual.name shouldBe "Casual"
    Tone.Friendly.name shouldBe "Friendly"
    Tone.Formal.name shouldBe "Formal"
    Tone.Excited.name shouldBe "Excited"
    Tone.Neutral.name shouldBe "Neutral"
  }
}
// scalastyle:on line.size.limit
