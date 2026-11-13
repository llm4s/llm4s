package org.llm4s.portable

import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * Runs unchanged on the JVM, Scala.js and Scala Native (`sbt test` in this build runs all three). Each expected
 * string is the one the JVM writes, so a pass on every platform means core's own upickle codecs write the same
 * bytes everywhere and read them back to equal values.
 */
class CrossPlatformJsonSpec extends AnyFlatSpec with Matchers {
  import Fixtures._

  private val conversationJson =
    s"""{"messages":[{"type":"system","content":"You are terse."},""" +
      s"""{"type":"user","content":"Weather in Paris? $nonAscii"},""" +
      """{"type":"assistant","data":{"contentOpt":null,"toolCalls":[{"id":"call_1","name":"get_weather","arguments":{"city":"Paris","days":3}}]}},""" +
      """{"type":"tool","toolCallId":"call_1","content":"{\"temp\":21.5}"},""" +
      """{"type":"user","parts":[{"type":"text","text":"And this picture?"},{"type":"image","mediaType":"image/png","source":{"type":"url","uri":"https://example.com/cat.png"}}]},""" +
      """{"type":"assistant","data":{"contentOpt":"Sunny, 21.5 C.","toolCalls":[]}}]}"""

  "Conversation" should "write the same JSON on every platform" in {
    write(conversation) shouldBe conversationJson
  }

  it should "read that JSON back to an equal value" in {
    read[Conversation](conversationJson) shouldBe conversation
    read[Conversation](write(conversation)).messages.map(_.role) shouldBe conversation.messages.map(_.role)
  }

  "ToolCall" should "round-trip its arguments as JSON, not as a string" in {
    val json = """{"id":"call_1","name":"get_weather","arguments":{"city":"Paris","days":3}}"""
    write(weatherCall) shouldBe json
    read[ToolCall](json) shouldBe weatherCall
  }

  "ReasoningEffort" should "round-trip every level" in {
    val levels: Seq[ReasoningEffort] =
      Seq(ReasoningEffort.None, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High)
    levels.map(l => read[ReasoningEffort](write[ReasoningEffort](l))) shouldBe levels
  }

  "A tool schema" should "render the same OpenAI tool definition on every platform" in {
    ujson.write(weatherTool.toOpenAITool(strict = true)) shouldBe
      """{"type":"function","function":{"name":"get_weather","description":"Current weather for a city",""" +
      """"parameters":{"type":"object","description":"Weather query","properties":{"city":{"type":"string",""" +
      """"description":"City name"},"days":{"type":"integer","description":"Days ahead"}},"required":["city","days"],""" +
      """"additionalProperties":false},"strict":true}}"""
  }

  it should "run its handler on parsed arguments" in {
    weatherTool.execute(weatherCall.arguments) shouldBe Right(ujson.Str("Sunny in Paris"))
  }

  "Message factories and LLMError" should "validate and describe errors the same way" in {
    val empty: Result[UserMessage] = Message.user("  ")
    empty.isLeft shouldBe true
    val error: LLMError = ServiceError(503, "ollama", "overloaded")
    LLMError.isRecoverable(error) shouldBe true
    error.message should include("overloaded")
  }
}
