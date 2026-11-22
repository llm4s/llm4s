package org.llm4s.llmconnect.provider

import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, sendSseResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RFC 0009 PR 2 for `AnthropicClient`: `stop_reason` is mapped on `complete`, and on a stream from `message_delta`,
 * where before the client reported `Other("")`. A `refusal` is a `ContentFilteredError` on both paths, with the
 * usage the call was billed.
 */
class AnthropicFinishReasonSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def clientAt(baseUrl: String) = new AnthropicClient(
    AnthropicConfig(
      apiKey = Secret("test-key"),
      model = "claude-sonnet-4-5",
      baseUrl = baseUrl,
      contextWindow = 200000,
      reserveCompletion = 4096
    )
  )

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def reply(stopReason: String): String =
    s"""{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
       |"content":[{"type":"text","text":"Hel"}],"stop_reason":"$stopReason","stop_sequence":null,
       |"usage":{"input_tokens":9,"output_tokens":4}}""".stripMargin

  private def stream(stopReason: String): String =
    Seq(
      "message_start" ->
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":9,"output_tokens":0}}}""",
      "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
      "message_delta" ->
        s"""{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":4}}""",
      "message_stop" -> """{"type":"message_stop"}"""
    ).map((event, data) => s"event: $event\ndata: $data\n\n").mkString

  private val mapped = Seq(
    "end_turn"                      -> FinishReason.Stop,
    "stop_sequence"                 -> FinishReason.Stop,
    "tool_use"                      -> FinishReason.ToolCalls,
    "max_tokens"                    -> FinishReason.Length,
    "model_context_window_exceeded" -> FinishReason.Length,
    "pause_turn"                    -> FinishReason.Other("pause_turn"),
    "brand_new"                     -> FinishReason.Other("brand_new")
  )

  "complete" should "map the reply's stop_reason" in {
    mapped.foreach { (raw, reason) =>
      withClue(raw) {
        withServer("/v1/messages")(sendJsonResponse(_, 200, reply(raw))) { baseUrl =>
          clientAt(baseUrl).complete(hi).map(_.finishReason) shouldBe Right(reason)
        }
      }
    }
  }

  it should "fail a refusal with a ContentFilteredError carrying the text it cut after and the usage" in {
    withServer("/v1/messages")(sendJsonResponse(_, 200, reply("refusal"))) { baseUrl =>
      clientAt(baseUrl).complete(hi) match {
        case Left(e: ContentFilteredError) =>
          e.provider shouldBe "Anthropic"
          e.reason shouldBe "refusal"
          e.promptBlocked shouldBe false
          e.message should include("after 3 characters")
          e.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((9, 4))
        case other => fail(s"expected a ContentFilteredError, got $other")
      }
    }
  }

  "streamComplete" should "carry message_delta's stop_reason on the message_stop chunk and the Completion" in {
    mapped.foreach { (raw, reason) =>
      withClue(raw) {
        val chunks = ListBuffer.empty[StreamedChunk]
        withServer("/v1/messages")(sendSseResponse(_, stream(raw))) { baseUrl =>
          clientAt(baseUrl).streamComplete(hi, onChunk = chunks += _).map(_.finishReason) shouldBe Right(reason)
        }
        chunks.flatMap(_.finishReason).toList shouldBe List(reason)
      }
    }
  }

  it should "fail a refusal, keeping the text already delivered, with the usage" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    withServer("/v1/messages")(sendSseResponse(_, stream("refusal"))) { baseUrl =>
      clientAt(baseUrl).streamComplete(hi, onChunk = chunks += _) match {
        case Left(e: ContentFilteredError) =>
          e.message should include("after 3 characters")
          e.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((9, 4))
        case other => fail(s"expected a ContentFilteredError, got $other")
      }
    }
    chunks.flatMap(_.content).mkString shouldBe "Hel"
    chunks.flatMap(_.finishReason) shouldBe empty
  }
}
