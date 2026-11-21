package org.llm4s.llmconnect.provider

import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, FinishReason, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RFC 0009 Part 1 for `OpenAIClient`: `complete` reports the choice's `finish_reason` on the `Completion` (it read none
 * before), a stream's chunks carry it typed, and fake streaming passes the reply's own reason on instead of a made-up
 * `"stop"`.
 */
final class OpenAIClientFinishReasonSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def configFor(model: String) =
    OpenAIConfig
      .fromValues(modelName = model, apiKey = Secret("test-api-key"), baseUrl = "https://example.invalid/v1")
      .value

  private def reply(finish: String) = completionOf(
    s"""{"id":"chatcmpl-1","created":0,"model":"gpt-4o",
       |"choices":[{"index":0,"message":{"role":"assistant","content":"Hel"}$finish}]}""".stripMargin
  )

  private def complete(model: String, finish: String) =
    OpenAIClient
      .forTest(model, transport(complete = _ => reply(finish)), configFor(model))
      .complete(hi, CompletionOptions())

  "OpenAIClient.complete" should "report the choice's finish_reason, mapped" in {
    complete("gpt-4o", ""","finish_reason":"length"""").value.finishReason shouldBe FinishReason.Length
    complete("gpt-4o", ""","finish_reason":"stop"""").value.finishReason shouldBe FinishReason.Stop
    complete("gpt-4o", ""","finish_reason":"content_filter"""").value.finishReason shouldBe
      FinishReason.Other("content_filter")
  }

  it should "report Other(\"\") when the reply has no finish_reason" in {
    complete("gpt-4o", "").value.finishReason shouldBe FinishReason.Other("")
  }

  "OpenAIClient.streamComplete" should "carry the typed reason on the chunk and the Completion" in {
    val events = Seq(
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
      ),
      chunk(
        """{"id":"c1","object":"chat.completion.chunk","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}"""
      )
    )
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("gpt-4o", transport(streaming = _ => stream(events*)), configFor("gpt-4o"))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    chunks.flatMap(_.finishReason).toList shouldBe List(FinishReason.Length)
    result.value.finishReason shouldBe FinishReason.Length
  }

  "fake streaming (o1)" should "pass the reply's own reason on, not a made-up stop" in {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = OpenAIClient
      .forTest("o1", transport(complete = _ => reply(""","finish_reason":"length"""")), configFor("o1"))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    chunks.flatMap(_.finishReason).toList shouldBe List(FinishReason.Length)
    result.value.finishReason shouldBe FinishReason.Length
  }
}
