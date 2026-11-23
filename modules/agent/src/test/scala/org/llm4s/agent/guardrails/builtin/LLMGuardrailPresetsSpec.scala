package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ LLMGuardrail, ScoringJudgeClient }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.UserMessage
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMGuardrailPresetsSpec extends AnyFlatSpec with Matchers {

  final private case class Preset(
    label: String,
    build: LLMClient => LLMGuardrail,
    threshold: Double,
    above: String,
    below: String,
    checkPrompt: String => Assertion
  )

  private val originalQuery    = "How do lunar eclipses happen?"
  private val referenceContext = "Earth's shadow falls on the Moon during a lunar eclipse."
  private val content          = "Sample response."

  // the first of the five criteria every safety judge is given, whatever a preset adds
  private val baseSafetyCriterion = "1. Harmful or dangerous content"

  private def checkAllowedTones(prompt: String, expectedTones: Set[String]): Assertion = {
    val toneList = prompt.linesIterator
      .find(_.contains("one of these tones: "))
      .getOrElse(fail("Missing allowed-tone list in the evaluation prompt"))
    val tones = toneList.split(": ", 2).last.stripSuffix(".").split(",").map(_.trim).toSet
    tones shouldBe expectedTones
  }

  private val presets = Seq(
    Preset(
      "LLMQualityGuardrail.highQuality",
      client => LLMQualityGuardrail.highQuality(client, originalQuery),
      0.85,
      "0.86",
      "0.84",
      prompt => prompt should include(originalQuery)
    ),
    Preset(
      "LLMSafetyGuardrail.childSafe",
      LLMSafetyGuardrail.childSafe,
      0.95,
      "0.96",
      "0.94",
      prompt =>
        prompt should (include(baseSafetyCriterion)
          .and(include("Additional criteria:"))
          .and(include("6. Age-inappropriate themes"))
          .and(include("7. Scary or disturbing content"))
          .and(include("8. Complex adult topics")))
    ),
    Preset(
      "LLMSafetyGuardrail.strict",
      LLMSafetyGuardrail.strict,
      0.95,
      "0.96",
      "0.94",
      prompt => prompt should (include(baseSafetyCriterion).and(not(include("Additional criteria:"))))
    ),
    Preset(
      "LLMToneGuardrail.professional",
      client => LLMToneGuardrail.professional(client),
      0.70,
      "0.71",
      "0.69",
      prompt => checkAllowedTones(prompt, Set("professional", "formal", "business-appropriate"))
    ),
    Preset(
      "LLMToneGuardrail.friendly",
      client => LLMToneGuardrail.friendly(client),
      0.70,
      "0.71",
      "0.69",
      prompt => checkAllowedTones(prompt, Set("friendly", "warm", "approachable"))
    ),
    Preset(
      "LLMToneGuardrail.professionalOrFriendly",
      client => LLMToneGuardrail.professionalOrFriendly(client),
      0.70,
      "0.71",
      "0.69",
      prompt => checkAllowedTones(prompt, Set("professional", "friendly", "warm", "approachable"))
    ),
    Preset(
      "LLMFactualityGuardrail.strict",
      client => LLMFactualityGuardrail.strict(client, referenceContext),
      0.90,
      "0.91",
      "0.89",
      prompt => prompt should include(referenceContext)
    ),
    Preset(
      "LLMFactualityGuardrail.lenient",
      client => LLMFactualityGuardrail.lenient(client, referenceContext),
      0.50,
      "0.51",
      "0.49",
      prompt => prompt should include(referenceContext)
    )
  )

  // The tone presets take a threshold; the table above builds them with the default, which is also the class's.
  private val tonePresets: Seq[(String, (LLMClient, Double) => LLMGuardrail)] = Seq(
    "LLMToneGuardrail.professional" -> ((client, threshold) => LLMToneGuardrail.professional(client, threshold)),
    "LLMToneGuardrail.friendly"     -> ((client, threshold) => LLMToneGuardrail.friendly(client, threshold)),
    "LLMToneGuardrail.professionalOrFriendly" -> ((client, threshold) =>
      LLMToneGuardrail.professionalOrFriendly(client, threshold)
    )
  )

  presets.foreach { preset =>
    preset.label should "use the expected threshold" in {
      val guardrail = preset.build(new ScoringJudgeClient(preset.above))
      guardrail.threshold shouldBe preset.threshold
    }

    it should "include the preset criteria in the evaluation prompt" in {
      val guardrail = preset.build(new ScoringJudgeClient(preset.above))
      preset.checkPrompt(guardrail.evaluationPrompt)
    }

    it should s"pass a score of ${preset.above} and send the evaluation prompt to the judge" in {
      val client    = new ScoringJudgeClient(preset.above)
      val guardrail = preset.build(client)
      guardrail.validate(content) shouldBe Right(content)

      val judgePrompt = client.lastConversation
        .flatMap(_.messages.collectFirst { case message: UserMessage => message.content })
        .getOrElse(fail("Expected a user message in the judge request"))
      judgePrompt should include(guardrail.evaluationPrompt)
    }

    it should s"reject a score of ${preset.below} as below the threshold" in {
      val guardrail = preset.build(new ScoringJudgeClient(preset.below))
      guardrail.validate(content) match {
        case Left(error: ValidationError) => error.field shouldBe "output"
        case other                        => fail(s"Expected a below-threshold ValidationError, got $other")
      }
    }
  }

  tonePresets.foreach { case (label, build) =>
    label should "use a threshold the caller gives in place of the default" in {
      build(new ScoringJudgeClient("0.91"), 0.9).threshold shouldBe 0.9
      build(new ScoringJudgeClient("0.91"), 0.9).validate(content) shouldBe Right(content)
      build(new ScoringJudgeClient("0.89"), 0.9).validate(content) match {
        case Left(error: ValidationError) => error.field shouldBe "output"
        case other                        => fail(s"Expected a below-threshold ValidationError, got $other")
      }
    }
  }
}
