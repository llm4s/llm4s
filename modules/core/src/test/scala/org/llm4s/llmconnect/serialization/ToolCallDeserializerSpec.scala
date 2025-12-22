package org.llm4s.llmconnect.serialization

import org.llm4s.llmconnect.LLMConnectTestFixtures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  test("OpenRouterToolCallDeserializer should parse nested array format") {
    val json      = ujson.read(LLMConnectTestFixtures.ToolCallJson.openRouterFormat)
    val toolCalls = OpenRouterToolCallDeserializer.deserializeToolCalls(json)

    toolCalls.length shouldBe 1
    toolCalls.head.id shouldBe "call_abc123"
    toolCalls.head.name shouldBe "get_weather"
    toolCalls.head.arguments("location").str shouldBe "San Francisco"
  }

  test("StandardToolCallDeserializer should handle empty array") {
    val json      = ujson.read("[]")
    val toolCalls = StandardToolCallDeserializer.deserializeToolCalls(json)
    toolCalls shouldBe empty
  }

  test("OpenRouterToolCallDeserializer should handle empty nested array") {
    val json      = ujson.read("[[]]")
    val toolCalls = OpenRouterToolCallDeserializer.deserializeToolCalls(json)
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
}
