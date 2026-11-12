package org.llm4s.portable

import org.llm4s.error.{ AuthenticationError, ProcessingError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class OpenAIChatProtocolSpec extends AnyFlatSpec with Matchers {
  import Fixtures._

  /** An Ollama-shaped stream: text deltas, a tool call whose continuation carries only its index, then usage. */
  private val stream =
    Seq(
      """data: {"id":"c1","model":"llama3","created":1700000000,"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":"}}]}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\",\"days\":3}"}}]}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
      """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}""",
      "data: [DONE]"
    ).map(_ + "\n\n").mkString

  "requestBody" should "map core's conversation and options to the chat-completions body" in {
    val options = CompletionOptions().withMaxTokens(64).withTools(Seq(weatherTool))
    OpenAIChatProtocol
      .requestBody("llama3", textConversation, options, stream = true)
      .map(ujson.write(_)) shouldBe Right(
      s"""{"model":"llama3","messages":[{"role":"system","content":"You are terse."},""" +
        s"""{"role":"user","content":"Weather in Paris? $nonAscii"},""" +
        """{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\",\"days\":3}"}}]},""" +
        """{"role":"tool","tool_call_id":"call_1","content":"{\"temp\":21.5}"}],""" +
        """"temperature":0.7,"top_p":1,"stream":true,"max_tokens":64,""" +
        """"tools":[{"type":"function","function":{"name":"get_weather","description":"Current weather for a city",""" +
        """"parameters":{"type":"object","description":"Weather query","properties":{"city":{"type":"string",""" +
        """"description":"City name"},"days":{"type":"integer","description":"Days ahead"}},"required":["city","days"],""" +
        """"additionalProperties":false},"strict":false}}],"stream_options":{"include_usage":true}}"""
    )
  }

  it should "refuse a media part rather than drop it" in {
    OpenAIChatProtocol.requestBody("m", conversation, CompletionOptions(), stream = false) match {
      case Left(e: ValidationError) => e.message should include("image")
      case other                    => fail(s"expected a ValidationError, got $other")
    }
  }

  "decodeCompletion" should "read text, tool calls, usage and the creation time" in {
    val body =
      """{"id":"chatcmpl-1","created":1700000000,"model":"llama3","choices":[{"index":0,"finish_reason":"tool_calls",""" +
        """"message":{"role":"assistant","content":"Checking.","tool_calls":[{"id":"call_1","type":"function",""" +
        """"function":{"name":"get_weather","arguments":"{\"city\":\"Paris\",\"days\":3}"}}]}}],""" +
        """"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}"""
    val completion = OpenAIChatProtocol.decodeCompletion(body).fold(e => fail(e.message), identity)
    completion.id shouldBe "chatcmpl-1"
    completion.created shouldBe Instant.ofEpochSecond(1700000000L)
    completion.content shouldBe "Checking."
    completion.toolCalls shouldBe List(weatherCall)
    completion.message shouldBe AssistantMessage("Checking.", Seq(weatherCall))
    completion.usage shouldBe Some(TokenUsage(promptTokens = 12, completionTokens = 5, totalTokens = 17))
  }

  it should "turn a body that is not a chat completion into a ProcessingError" in {
    OpenAIChatProtocol.decodeCompletion("<html>").left.map(_.getClass) shouldBe Left(classOf[ProcessingError])
    OpenAIChatProtocol.decodeCompletion("""{"choices":[]}""").left.map(_.getClass) shouldBe Left(
      classOf[ProcessingError]
    )
  }

  "decodeError" should "map the status to core's error types" in {
    val body = """{"error":{"message":"bad key"}}"""
    OpenAIChatProtocol.decodeError(401, body, "ollama") shouldBe a[AuthenticationError]
    OpenAIChatProtocol.decodeError(429, body, "ollama") shouldBe a[RateLimitError]
    val service = OpenAIChatProtocol.decodeError(500, body, "ollama")
    service shouldBe a[ServiceError]
    service.message should include("bad key")
  }

  "StreamDecoder" should "emit core's StreamedChunks and assemble the Completion, however the body is split" in {
    for (pieceSize <- Seq(1, 7, stream.length)) {
      val decoder = new OpenAIChatProtocol.StreamDecoder
      val chunks  = stream.grouped(pieceSize).toVector.flatMap(decoder.feed)
      chunks.flatMap(_.content).mkString shouldBe "Hello"
      chunks.flatMap(_.toolCall).map(c => (c.id, c.name)).distinct shouldBe Vector(("call_1", "get_weather"))
      chunks.last.finishReason shouldBe Some("tool_calls")
      val completion = decoder.completion.fold(e => fail(e.message), identity)
      completion.content shouldBe "Hello"
      completion.toolCalls shouldBe List(weatherCall)
      completion.usage shouldBe Some(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7))
      completion.created shouldBe Instant.ofEpochSecond(1700000000L)
    }
  }

  it should "report an error event and an empty stream as errors" in {
    val failed = new OpenAIChatProtocol.StreamDecoder
    failed.feed("data: {\"error\":{\"message\":\"model not found\"}}\n\n") shouldBe empty
    failed.completion.left.map(_.message).swap.getOrElse("") should include("model not found")
    new OpenAIChatProtocol.StreamDecoder().completion.isLeft shouldBe true
  }
}
