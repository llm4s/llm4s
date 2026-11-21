package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RFC 0009 Part 1, the chat-completions row: `stop`, `tool_calls` (and the older `function_call`) and `length` map to
 * their `FinishReason`; any other value, `content_filter` included until PR 2, is kept as `Other`. A reply that sent
 * no reason is `Other("")`, never a made-up `Stop`.
 */
class OpenAICompatibleFinishReasonSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1/v1", None)),
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

  "complete" should "map the choice's finish_reason" in {
    mapped.foreach { case (raw, reason) =>
      withClue(raw) {
        client.parseCompletion(reply(s""","finish_reason":"$raw"""")).finishReason shouldBe reason
      }
    }
  }

  it should "give Other(\"\") for a reply with no finish_reason, a null or a blank one" in {
    Seq("", ""","finish_reason":null""", ""","finish_reason":""""").foreach { finish =>
      withClue(finish) {
        client.parseCompletion(reply(finish)).finishReason shouldBe FinishReason.Other("")
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
}
