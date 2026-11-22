package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * RFC 0008: what the request does not send is reported on `Completion.adjustments`, never dropped silently. Jlama is
 * given the temperature and the token limit only (see the client's Scaladoc).
 */
class JlamaAdjustmentsSpec extends AnyWordSpec with Matchers:

  import JlamaTestEngines.*

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def kinds(options: CompletionOptions, streamed: Boolean = false): List[(RequestOption, String)] = {
    val c = client(new ScriptedEngine())
    val result =
      if (streamed) c.streamComplete(hello, options, _ => ())
      else c.complete(hello, options)
    result.fold(e => fail(e.message), _.adjustments).map(a => a.option -> a.getClass.getSimpleName)
  }

  "the adjustments" should {

    "be none for what Jlama is sent: the temperature and the token limit" in {
      kinds(CompletionOptions().withTemperature(0.2).withMaxTokens(64)) shouldBe Nil
    }

    "report each option it is not sent as Dropped" in {
      val schema = ResponseFormat.Json
      val options = CompletionOptions()
        .withTopP(0.5)
        .withPresencePenalty(0.5)
        .withFrequencyPenalty(0.3)
        .withResponseFormat(schema)
        .withReasoning(ReasoningEffort.High)
        .withThinking(Thinking.Adaptive)
      kinds(options) should contain theSameElementsAs List(
        RequestOption.TopP             -> "Dropped",
        RequestOption.PresencePenalty  -> "Dropped",
        RequestOption.FrequencyPenalty -> "Dropped",
        RequestOption.ResponseFormat   -> "Dropped",
        RequestOption.Reasoning        -> "Dropped",
        RequestOption.Thinking         -> "Dropped"
      )
    }

    "report a service tier Dropped, since a local engine has none (#2726)" in {
      kinds(CompletionOptions().withServiceTier(ServiceTier.Flex)) shouldBe List(RequestOption.ServiceTier -> "Dropped")
    }

    "be the same on a stream as on complete" in {
      val options = CompletionOptions().withTopP(0.5).withReasoning(ReasoningEffort.Low)
      kinds(options, streamed = true) shouldBe kinds(options)
      kinds(options, streamed = true) should not be empty
    }
  }
