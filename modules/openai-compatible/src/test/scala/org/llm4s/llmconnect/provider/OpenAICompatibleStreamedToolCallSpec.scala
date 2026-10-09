package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ DeepSeekConfig, OpenAICompatibleConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, ToolCall, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * A streamed tool call arrives split across deltas: only the first names the call's `id` and
 * `name`, and continuations identify it by `index`. Every continuation must reach the caller
 * and the final `Completion` under the call's id, with calls interleaved by index kept apart.
 * Each of the three clients this module replaced defaulted a continuation's missing id to `""`,
 * so `StreamingAccumulator` dropped its arguments (#1132, PR #1207 review).
 */
class OpenAICompatibleStreamedToolCallSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conversation = Conversation(Seq(UserMessage("weather?")))

  private def first(index: Int, id: String, name: String, args: String) =
    s"""{"id":"s","choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,"id":"$id","type":"function","function":{"name":"$name","arguments":${ujson
        .Str(args)
        .render()}}}]}}]}"""

  private def more(index: Int, args: String) =
    s"""{"id":"s","choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,"function":{"arguments":${ujson
        .Str(args)
        .render()}}}]}}]}"""

  private val finish = """{"id":"s","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

  private def sse(events: String*) = events.map(e => s"data: $e\n\n").mkString + "data: [DONE]\n\n"

  private def stream(events: String*): (Seq[StreamedChunk], Seq[ToolCall]) = {
    var result: (Seq[StreamedChunk], Seq[ToolCall]) = (Nil, Nil)
    withServer("/chat/completions")(exchange => sendSseResponse(exchange, sse(events*))) { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new OpenAICompatibleClient(
        OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", baseUrl)),
        OpenAICompatibleDialect.Standard
      ).streamComplete(conversation, CompletionOptions(), chunks += _).value
      result = (chunks.toSeq, completion.toolCalls)
    }
    result
  }

  "a streamed tool call split across deltas" should "reach the completion with its whole arguments" in {
    val (chunks, toolCalls) = stream(
      first(0, "call_1", "get_weather", ""),
      more(0, """{"loca"""),
      more(0, """tion": """),
      more(0, """"Paris"""),
      more(0, "\"}"),
      finish
    )

    toolCalls shouldBe Seq(ToolCall("call_1", "get_weather", ujson.Obj("location" -> "Paris")))
    // Every continuation chunk handed to the caller names its call.
    chunks.flatMap(_.toolCall).map(tc => (tc.id, tc.name)).distinct shouldBe Seq(("call_1", "get_weather"))
    chunks.flatMap(_.toolCall) should have size 5
  }

  it should "hand the caller fragments that reassemble, even one that is valid JSON on its own" in {
    // `":"` is a JSON string; parsed before it reached the caller it became `:` and the caller's
    // reassembly `{"topic:vault"}` (#1212, PR #1406 review).
    val (chunks, toolCalls) = stream(
      first(0, "call_1", "lookup", ""),
      more(0, "{\""),
      more(0, "topic"),
      more(0, "\":\""),
      more(0, "vault"),
      more(0, "\"}"),
      finish
    )

    toolCalls shouldBe Seq(ToolCall("call_1", "lookup", ujson.Obj("topic" -> "vault")))
    chunks.flatMap(_.toolCall).map(_.arguments).collect { case ujson.Str(s) => s }.mkString shouldBe
      """{"topic":"vault"}"""
    val accumulator = org.llm4s.llmconnect.streaming.StreamingAccumulator.create()
    chunks.foreach(accumulator.addChunk)
    accumulator.currentToolCalls shouldBe toolCalls
  }

  it should "keep two calls interleaved by index apart" in {
    val (chunks, toolCalls) = stream(
      first(0, "call_a", "get_weather", """{"city":"""),
      first(1, "call_b", "get_time", """{"tz":"""),
      more(0, "\"Oslo\"}"),
      more(1, "\"CET\"}"),
      finish
    )

    toolCalls.sortBy(_.id) shouldBe Seq(
      ToolCall("call_a", "get_weather", ujson.Obj("city" -> "Oslo")),
      ToolCall("call_b", "get_time", ujson.Obj("tz" -> "CET"))
    )
    chunks.flatMap(_.toolCall).map(_.id) shouldBe Seq("call_a", "call_b", "call_a", "call_b")
  }

  it should "keep calls apart when one event carries fragments of several" in {
    val (_, toolCalls) = stream(
      """{"id":"s","choices":[{"delta":{"tool_calls":[
        |{"index":0,"id":"c0","type":"function","function":{"name":"f","arguments":"{\"a\":"}},
        |{"index":1,"id":"c1","type":"function","function":{"name":"g","arguments":"{\"b\":"}}]}}]}""".stripMargin
        .replace("\n", ""),
      """{"id":"s","choices":[{"delta":{"tool_calls":[
        |{"index":1,"function":{"arguments":"2}"}},{"index":0,"function":{"arguments":"1}"}}]}}]}""".stripMargin
        .replace("\n", ""),
      finish
    )

    toolCalls.sortBy(_.id) shouldBe Seq(
      ToolCall("c0", "f", ujson.Obj("a" -> 1)),
      ToolCall("c1", "g", ujson.Obj("b" -> 2))
    )
  }

  it should "work the same for a provider dialect (DeepSeek)" in
    withServer("/chat/completions") { exchange =>
      sendSseResponse(exchange, sse(first(0, "call_1", "lookup", """{"q":"""), more(0, "\"llm4s\"}"), finish))
    } { baseUrl =>
      val completion = new DeepSeekClient(DeepSeekConfig("k", "deepseek-chat", baseUrl, 128000, 8192))
        .streamComplete(conversation, CompletionOptions(), _ => ())
        .value
      completion.toolCalls shouldBe Seq(ToolCall("call_1", "lookup", ujson.Obj("q" -> "llm4s")))
    }

  "parseStreamingChunks" should "resolve a continuation only against the stream it belongs to" in {
    val client = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1/v1")),
      OpenAICompatibleDialect.Standard
    )
    val state = new OpenAICompatibleClient.StreamToolCalls
    client.parseStreamingChunks(ujson.read(first(0, "c", "f", "")), state)
    client.parseStreamingChunks(ujson.read(more(0, "{}")), state).flatMap(_.toolCall).map(_.id) shouldBe Seq("c")
    // A fresh stream knows nothing of `c`.
    client.parseStreamingChunks(ujson.read(more(0, "{}"))).flatMap(_.toolCall).map(_.id) shouldBe Seq("")
  }
}
