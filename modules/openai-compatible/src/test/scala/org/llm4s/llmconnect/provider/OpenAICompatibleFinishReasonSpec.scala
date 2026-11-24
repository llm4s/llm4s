package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, sendSseResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RFC 0009 Part 1, the chat-completions row: `stop`, `tool_calls` (and the older `function_call`) and `length` map to
 * their `FinishReason`; any other value is kept as `Other`. A reply that sent no reason is `Other("")`, never a
 * made-up `Stop`. `content_filter` is read as `Other("content_filter")`, and the client fails the call with a
 * `ContentFilteredError` on it (PR 2), on `complete` and on a stream alike.
 */
class OpenAICompatibleFinishReasonSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig(ModelName("m"), "http://localhost:1/v1", None)),
    OpenAICompatibleDialect.Standard
  )

  private val mapped = Seq(
    "stop"           -> FinishReason.Stop,
    "tool_calls"     -> FinishReason.ToolCalls,
    "function_call"  -> FinishReason.ToolCalls,
    "length"         -> FinishReason.Length,
    "content_filter" -> FinishReason.Other("content_filter"),
    "brand_new"      -> FinishReason.Other("brand_new")
  )

  private def reply(finish: String): ujson.Value =
    ujson.read(
      s"""{"id":"c1","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"Hi"}$finish}]}"""
    )

  private def clientAt(baseUrl: String) = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig(ModelName("m"), baseUrl, None)),
    OpenAICompatibleDialect.Standard
  )

  private val usage = """"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}"""

  "complete" should "map the choice's finish_reason" in {
    mapped.foreach { case (raw, reason) =>
      withClue(raw) {
        client.parsedCompletion(reply(s""","finish_reason":"$raw"""")).finishReason shouldBe reason
      }
    }
  }

  it should "give Other(\"\") for a reply with no finish_reason, a null or a blank one" in {
    Seq("", ""","finish_reason":null""", ""","finish_reason":""""").foreach { finish =>
      withClue(finish) {
        client.parsedCompletion(reply(finish)).finishReason shouldBe FinishReason.Other("")
      }
    }
  }

  "a streamed chunk" should "carry the mapped reason, and none while the choice has none" in {
    def chunk(finish: String) =
      client.parseStreamingChunks(
        ujson.read(s"""{"id":"c1","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":$finish}]}""")
      )
    mapped.foreach { case (raw, reason) =>
      withClue(raw)(chunk(s""""$raw"""").map(_.finishReason) shouldBe Seq(Some(reason)))
    }
    chunk("null").map(_.finishReason) shouldBe Seq(None)
  }

  "a content_filter finish" should "fail complete with a ContentFilteredError carrying the billed usage" in {
    val body =
      s"""{"id":"c1","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"Here"},"finish_reason":"content_filter"}],$usage}"""
    withServer("/")(sendJsonResponse(_, 200, body)) { baseUrl =>
      clientAt(baseUrl).complete(Conversation(Seq(UserMessage("Hi")))) match {
        case Left(e: ContentFilteredError) =>
          e.reason shouldBe "content_filter"
          e.promptBlocked shouldBe false
          e.message should include("after 4 characters")
          e.usage.map(_.totalTokens) shouldBe Some(13)
        case other => fail(s"expected a ContentFilteredError, got $other")
      }
    }
  }

  it should "fail a stream, keeping the text already delivered, with the usage sent after it" in {
    val stream = Seq(
      """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"Here"},"finish_reason":null}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}""",
      s"""data: {"id":"c1","choices":[],$usage}""",
      "data: [DONE]"
    ).mkString("", "\n\n", "\n\n")
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    withServer("/")(sendSseResponse(_, stream)) { baseUrl =>
      clientAt(baseUrl).streamComplete(Conversation(Seq(UserMessage("Hi"))), onChunk = chunks += _) match {
        case Left(e: ContentFilteredError) =>
          e.message should include("after 4 characters")
          e.usage.map(_.totalTokens) shouldBe Some(13)
        case other => fail(s"expected a ContentFilteredError, got $other")
      }
    }
    chunks.flatMap(_.content).mkString shouldBe "Here"
    chunks.flatMap(_.finishReason) shouldBe empty
  }
}
