package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * Streamed tool calls (#1132).
 *
 * A streamed tool call arrives split across deltas: the first carries its `id`, `name` and the
 * start of its arguments, and every continuation only its `index` and the next fragment. On the
 * Azure SDK `OpenAIClient` keyed calls by `id`, which continuations lack, so their fragments -
 * usually all of the arguments - were dropped, and `Completion.toolCalls` was empty on streams.
 */
final class OpenAIClientStreamToolCallSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val model = "gpt-4o"
  private val config =
    OpenAIConfig.fromValues(model, "test-api-key", None, "https://example.invalid/v1").value

  /** A chunk whose delta carries the given `tool_calls` entries. */
  private def toolDelta(calls: String*): String =
    s"""{"id":"chatcmpl-tc","created":0,"model":"$model",
       |"choices":[{"index":0,"delta":{"tool_calls":[${calls.mkString(",")}]}}]}""".stripMargin

  private def opening(index: Int, id: String, name: String, arguments: String): String =
    s"""{"index":$index,"id":"$id","type":"function","function":{"name":"$name","arguments":${ujson
        .Str(arguments)
        .render()}}}"""

  private def continuation(index: Int, arguments: String): String =
    s"""{"index":$index,"function":{"arguments":${ujson.Str(arguments).render()}}}"""

  private val finish =
    s"""{"id":"chatcmpl-tc","created":0,"model":"$model",
       |"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],
       |"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}""".stripMargin

  private def run(events: String*): (org.llm4s.llmconnect.model.Completion, Seq[StreamedChunk]) = {
    val received = ListBuffer.empty[StreamedChunk]
    val client   = OpenAIClient.forTest(model, transport(streaming = _ => stream(events.map(chunk)*)), config)
    val result = client.streamComplete(
      Conversation(Seq(UserMessage("weather?"))),
      CompletionOptions(),
      received += _
    )
    (result.value, received.toList)
  }

  "OpenAIClient.streamComplete" should "reassemble one tool call split across deltas" in {
    val (completion, chunks) = run(
      toolDelta(opening(0, "call_1", "get_weather", "")),
      toolDelta(continuation(0, """{"city":""")),
      // A fragment that is itself valid JSON: it must be concatenated verbatim, not re-rendered.
      toolDelta(continuation(0, "\"Paris\"")),
      toolDelta(continuation(0, "}")),
      finish
    )

    completion.toolCalls should have size 1
    val call = completion.toolCalls.head
    call.id shouldBe "call_1"
    call.name shouldBe "get_weather"
    call.arguments shouldBe ujson.Obj("city" -> "Paris")

    // Every tool-call chunk the caller saw names its call, continuations included.
    val toolChunks = chunks.flatMap(_.toolCall)
    toolChunks should have size 4
    toolChunks.map(_.id).distinct shouldBe Seq("call_1")
    toolChunks.map(_.name).distinct shouldBe Seq("get_weather")
  }

  it should "keep two interleaved tool calls apart by index" in {
    val (completion, chunks) = run(
      toolDelta(opening(0, "call_a", "get_weather", """{"ci""")),
      toolDelta(opening(1, "call_b", "get_time", """{"zone":""")),
      toolDelta(continuation(0, """ty":"Paris"}"""), continuation(1, "\"CET\"")),
      toolDelta(continuation(1, "}")),
      finish
    )

    completion.toolCalls.map(tc => tc.id -> (tc.name, tc.arguments)).toMap shouldBe Map(
      "call_a" -> ("get_weather", ujson.Obj("city" -> "Paris")),
      "call_b" -> ("get_time", ujson.Obj("zone" -> "CET"))
    )
    chunks.flatMap(_.toolCall).map(tc => tc.id -> tc.name).distinct.toMap shouldBe Map(
      "call_a" -> "get_weather",
      "call_b" -> "get_time"
    )
  }

  it should "put parseable arguments on the final completion, on the message and on toolCalls alike" in {
    val (completion, _) = run(
      toolDelta(opening(0, "call_1", "search", """{"query":"scala","""), opening(1, "call_2", "add", "{")),
      toolDelta(continuation(0, """"limit":10}"""), continuation(1, """"a":5,"b":3}""")),
      finish
    )

    completion.toolCalls should not be empty
    completion.toolCalls.toSet shouldBe completion.message.toolCalls.toSet
    completion.toolCalls.foreach(tc => tc.arguments shouldBe a[ujson.Obj])
    completion.toolCalls.find(_.id == "call_1").map(_.arguments("limit").num) shouldBe Some(10.0)
    completion.toolCalls.find(_.id == "call_2").map(_.arguments("b").num) shouldBe Some(3.0)
    completion.usage.map(_.totalTokens) shouldBe Some(19)
    completion.model shouldBe model
  }

  it should "leave a stream without tool calls with none" in {
    val (completion, _) = run(
      s"""{"id":"chatcmpl-tc","created":0,"model":"$model","choices":[{"index":0,"delta":{"content":"hi"}}]}""",
      finish
    )

    completion.content shouldBe "hi"
    completion.toolCalls shouldBe empty
  }

  it should "return three or more interleaved tool calls in index order" in {
    // Ids chosen so hash order and index order disagree: `StreamingAccumulator` kept partial calls
    // in an unordered map before #1132, so `Completion.toolCalls` came back in hash order.
    val ids = Seq("call_zeta", "call_alpha", "call_mu", "call_beta")
    val (completion, _) = run(
      (Seq(toolDelta(ids.zipWithIndex.map((id, i) => opening(i, id, s"tool_$i", """{"n":"""))*)) ++
        ids.indices.reverse.map(i => toolDelta(continuation(i, s"$i}"))) :+ finish)*
    )

    completion.toolCalls.map(_.id) shouldBe ids
    completion.toolCalls.map(_.name) shouldBe ids.indices.map(i => s"tool_$i")
    completion.toolCalls.map(_.arguments("n").num.toInt) shouldBe ids.indices
    completion.message.toolCalls.map(_.id) shouldBe ids
  }
}
