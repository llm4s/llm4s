package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class LLMGuardrailPresetsSpec extends AnyFlatSpec with Matchers {

  final private class MockLLMClient(scoreToReturn: String) extends LLMClient {
    var lastConversation: Option[Conversation] = None

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      lastConversation = Some(conversation)
      Right(
        Completion(
          id = "test-id",
          created = Instant.EPOCH,
          content = scoreToReturn,
          model = "test-model",
          message = AssistantMessage(scoreToReturn),
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

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

  private def checkAllowedTones(prompt: String, expectedTones: Set[String]): Assertion = {
    val toneList = prompt.linesIterator
      .find(_.contains("one of these tones: "))
      .getOrElse(fail("Missing allowed-tone list in the evaluation prompt"))
    val tones = toneList.split(": ", 2).last.stripSuffix(".").split(",").map(_.trim).toSet
    tones shouldBe expectedTones
  }

  private val presets = Seq(
    Preset(
      "LLMQualityGuardrail.apply",
      client => LLMQualityGuardrail(client, originalQuery),
      0.70,
      "0.71",
      "0.69",
      prompt => prompt should include(originalQuery)
    ),
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
        prompt should (include("6. Age-inappropriate themes")
          .and(include("7. Scary or disturbing content"))
          .and(include("8. Complex adult topics")))
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
      "LLMFactualityGuardrail.lenient",
      client => LLMFactualityGuardrail.lenient(client, referenceContext),
      0.50,
      "0.51",
      "0.49",
      prompt => prompt should include(referenceContext)
    )
  )

  presets.foreach { preset =>
    preset.label should "use the expected threshold" in {
      val guardrail = preset.build(new MockLLMClient(preset.above))
      guardrail.threshold shouldBe preset.threshold
    }

    it should "include the preset criteria in the evaluation prompt" in {
      val guardrail = preset.build(new MockLLMClient(preset.above))
      preset.checkPrompt(guardrail.evaluationPrompt)
    }

    it should s"pass a score of ${preset.above} and send the evaluation prompt to the judge" in {
      val client    = new MockLLMClient(preset.above)
      val guardrail = preset.build(client)
      guardrail.validate(content) shouldBe Right(content)

      val judgePrompt = client.lastConversation
        .flatMap(_.messages.collectFirst { case message: UserMessage => message.content })
        .getOrElse(fail("Expected a user message in the judge request"))
      judgePrompt should include(guardrail.evaluationPrompt)
    }

    it should s"reject a score of ${preset.below} as below the threshold" in {
      val guardrail = preset.build(new MockLLMClient(preset.below))
      guardrail.validate(content) match {
        case Left(error: ValidationError) => error.field shouldBe "output"
        case other                        => fail(s"Expected a below-threshold ValidationError, got $other")
      }
    }
  }
}
