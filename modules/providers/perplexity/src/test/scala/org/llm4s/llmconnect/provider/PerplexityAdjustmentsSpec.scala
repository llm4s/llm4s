package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer

/**
 * RFC 0008: what the request does not send as asked is reported on `Completion.adjustments`, never dropped silently.
 * The Agent API's `reasoning.effort` takes every level by its own name but has no "off"; it takes no thinking budget
 * and no penalties (see the client's Scaladoc).
 */
class PerplexityAdjustmentsSpec extends AnyWordSpec with Matchers:

  import PerplexityFixtures.*

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def adjustments(options: CompletionOptions): List[RequestAdjustment] =
    client(new StubHttp(respond = _ => ok(response(s"[${textMessage("ok")}]"))))
      .complete(hello, options)
      .fold(e => fail(e.message), _.adjustments)

  private def kinds(adjustments: List[RequestAdjustment]): List[(RequestOption, String)] =
    adjustments.map(a => a.option -> a.getClass.getSimpleName)

  "the adjustments" should {

    "be none for a reasoning level, which is sent by its own name" in {
      ReasoningEffort.values.foreach { level =>
        withClue(level.name)(adjustments(CompletionOptions().withReasoning(level)) shouldBe Nil)
      }
      adjustments(CompletionOptions()) shouldBe Nil
    }

    "be none for what the request sends: top_p, the token limit and a JSON schema" in {
      val schema  = ResponseFormat.JsonSchema(ujson.Obj("type" -> "object"))
      val options = CompletionOptions().withTopP(0.5).withMaxTokens(64).withResponseFormat(schema)
      adjustments(options) shouldBe Nil
    }

    "report Thinking.Off sent as minimal, with the reasoning level it overrode" in {
      val options = CompletionOptions().withReasoning(ReasoningEffort.High).withThinking(Thinking.Off)
      adjustments(options).collect { case RequestAdjustment.Adjusted(option, requested, sent, _) =>
        (option, requested, sent)
      } should contain theSameElementsAs List(
        (RequestOption.Thinking, "Off", "minimal"),
        (RequestOption.Reasoning, "high", "minimal")
      )
    }

    "report a thinking budget Dropped, and Adaptive NotNeeded since the effort decides" in {
      val budget = Thinking.budget(2048).fold(e => fail(e.message), identity)
      kinds(adjustments(CompletionOptions().withThinking(budget))) shouldBe List(RequestOption.Thinking -> "Dropped")
      kinds(adjustments(CompletionOptions().withThinking(Thinking.Adaptive))) shouldBe
        List(RequestOption.Thinking -> "NotNeeded")
    }

    "report penalties set away from 0 Dropped, since the Agent API takes none" in {
      val options = CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.3)
      kinds(adjustments(options)) should contain theSameElementsAs List(
        RequestOption.PresencePenalty  -> "Dropped",
        RequestOption.FrequencyPenalty -> "Dropped"
      )
    }

    "be the same on a stream as on complete" in {
      val options = CompletionOptions().withThinking(Thinking.Off).withFrequencyPenalty(0.3)
      val chunks  = ListBuffer.empty[StreamedChunk]
      val streamed =
        client(new StubHttp(stream = _ => sse(delta("ok"), completedEvent(response(s"[${textMessage("ok")}]")))))
          .streamComplete(hello, options, chunks += _)
          .fold(e => fail(e.message), _.adjustments)
      streamed shouldBe adjustments(options)
      streamed should not be empty
    }
  }
