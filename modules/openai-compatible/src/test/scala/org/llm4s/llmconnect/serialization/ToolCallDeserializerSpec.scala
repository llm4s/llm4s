package org.llm4s.llmconnect.serialization

import org.llm4s.llmconnect.LLMConnectTestFixtures
import org.llm4s.testutil.SmallStack
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.{ Failure, Success, Try }

class ToolCallDeserializerSpec extends AnyFunSuite with Matchers {

  test("StandardToolCallDeserializer should parse standard format tool calls") {
    val json      = ujson.read(LLMConnectTestFixtures.ToolCallJson.standardFormat)
    val toolCalls = StandardToolCallDeserializer.deserializeToolCalls(json)

    toolCalls.length shouldBe 1
    toolCalls.head.id shouldBe "call_abc123"
    toolCalls.head.name shouldBe "get_weather"
    toolCalls.head.arguments("location").str shouldBe "San Francisco"
  }

  test("StandardToolCallDeserializer should parse multiple tool calls") {
    val json      = ujson.read(LLMConnectTestFixtures.ToolCallJson.multipleToolCalls)
    val toolCalls = StandardToolCallDeserializer.deserializeToolCalls(json)

    toolCalls.length shouldBe 2
    toolCalls.head.id shouldBe "call_1"
    toolCalls.head.name shouldBe "get_weather"
    toolCalls(1).id shouldBe "call_2"
    toolCalls(1).name shouldBe "search_web"
  }

  test("StandardToolCallDeserializer should handle empty array") {
    val json      = ujson.read("[]")
    val toolCalls = StandardToolCallDeserializer.deserializeToolCalls(json)
    toolCalls shouldBe empty
  }

  test("StandardToolCallDeserializer should parse complex arguments") {
    val json = ujson.read("""[
      {
        "id": "call_complex",
        "type": "function",
        "function": {
          "name": "create_user",
          "arguments": "{\"name\":\"John\",\"age\":30,\"active\":true,\"tags\":[\"a\",\"b\"]}"
        }
      }
    ]""")

    val toolCalls = StandardToolCallDeserializer.deserializeToolCalls(json)

    toolCalls.length shouldBe 1
    toolCalls.head.id shouldBe "call_complex"
    toolCalls.head.name shouldBe "create_user"
    toolCalls.head.arguments("name").str shouldBe "John"
    toolCalls.head.arguments("age").num shouldBe 30
    toolCalls.head.arguments("active").bool shouldBe true
    toolCalls.head.arguments("tags").arr.length shouldBe 2
  }

  // The deserializer is strict: malformed arguments fail through an exception the client's `Try`
  // turns into a Left. Arguments nested too deeply must fail the same way, before they are parsed
  // into a value that overflows the stack of whatever renders it next (#1562). `Try` catches only
  // an Exception, so a caught failure proves it was not an Error. On a 1 MB stack, so deterministic;
  // the outcome is reduced to a message or a count inside it, so no deep value is ever rendered.
  test("StandardToolCallDeserializer refuses arguments nested too deeply with an Exception, before parsing them") {
    val deep = "[" * 100000 + "]" * 100000
    val json = ujson.Arr(
      ujson.Obj("id" -> "call_deep", "type" -> "function", "function" -> ujson.Obj("name" -> "f", "arguments" -> deep))
    )
    val outcome = SmallStack.run(
      Try(StandardToolCallDeserializer.deserializeToolCalls(json)) match {
        case Failure(e)     => Left(e.getMessage)
        case Success(calls) => Right(calls.size)
      }
    )
    outcome match {
      case Right(Left(message)) => message should include("512")
      case other                => fail(s"expected a caught Exception, got $other")
    }
  }

  test("StandardToolCallDeserializer parses arguments nested 512 levels deep") {
    val json = ujson.Arr(
      ujson.Obj(
        "id"       -> "call_512",
        "type"     -> "function",
        "function" -> ujson.Obj("name" -> "f", "arguments" -> ("[" * 512 + "]" * 512))
      )
    )
    StandardToolCallDeserializer.deserializeToolCalls(json).head.arguments shouldBe ujson.read("[" * 512 + "]" * 512)
  }
}
