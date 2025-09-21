package org.llm4s.trace

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// ... existing code ...

class LangfuseTracingSpec extends AnyFlatSpec with Matchers with MockFactory {
  // ... existing tests ...

  "LangfuseTracing.createEvent" should "create observation event for system message" in {
    val config      = stub[ConfigReader]
    val mockTracing = new MockLangfuseTracing(config)
    val systemMsg   = SystemMessage("System instruction")
    val messages    = Seq(systemMsg)
    val now         = "2024-01-01T00:00:00Z"
    val traceId     = "trace-123"

    val event = mockTracing.testCreateEvent(systemMsg, messages, 0, now, traceId, "gpt-4")

    (event.obj("body")("id").str) shouldBe "trace-123-event-0"
    (event.obj("type").str) shouldBe "event-create"
    (event.obj("body")("traceId").str) shouldBe traceId
    (event.obj("timestamp").str) shouldBe now
    (event.obj("body")("metadata")("role").str) shouldBe "system"
  }

  it should "create observation event for user message" in {
    val config      = stub[ConfigReader]
    val mockTracing = new MockLangfuseTracing(config)
    val userMsg     = UserMessage("User question")
    val messages    = Seq(userMsg)
    val now         = "2024-01-01T00:00:00Z"
    val traceId     = "trace-123"

    val event = mockTracing.testCreateEvent(userMsg, messages, 0, now, traceId, "gpt-4")

    (event.obj("type").str) shouldBe "event-create"
    (event.obj("body")("traceId").str) shouldBe traceId
    (event.obj("timestamp").str) shouldBe now
    (event.obj("body")("input")("content").str) shouldBe "User question"
    (event.obj("body")("metadata")("role").str) shouldBe "user"
  }

  it should "create observation event for assistant message with tool calls" in {
    val config       = stub[ConfigReader]
    val mockTracing  = new MockLangfuseTracing(config)
    val toolCall     = ToolCall("id1", "calculator", ujson.Obj("operation" -> "add", "a" -> 1, "b" -> 2))
    val assistantMsg = AssistantMessage(Some("Using calculator"), Seq(toolCall))
    val messages     = Seq(assistantMsg)
    val now          = "2024-01-01T00:00:00Z"
    val traceId      = "trace-123"

    val event = mockTracing.testCreateEvent(assistantMsg, messages, 0, now, traceId, "gpt-4")

    (event.obj("type").str) shouldBe "generation-create"
    (event.obj("body")("traceId").str) shouldBe traceId
    (event.obj("timestamp").str) shouldBe now
    (event.obj("body")("output")("content").str) shouldBe "Using calculator"
    (event.obj("body")("output")("role").str) shouldBe "assistant"
    (event.obj("body")("output")("tool_calls").arr.size) shouldBe 1
    (event.obj("body")("output")("tool_calls")(0)("id").str) shouldBe "id1"
    (event.obj("body")("output")("tool_calls")(0)("function")("name").str) shouldBe "calculator"
  }

  it should "create observation event for tool message" in {
    val config      = stub[ConfigReader]
    val mockTracing = new MockLangfuseTracing(config)
    val toolMsg     = ToolMessage("id1", """{"result": 3}""")
    val messages    = Seq(toolMsg)
    val now         = "2024-01-01T00:00:00Z"
    val traceId     = "trace-123"

    val event = mockTracing.testCreateEvent(toolMsg, messages, 0, now, traceId, "gpt-4")

    (event.obj("type").str) shouldBe "span-create"
    (event.obj("body")("traceId").str) shouldBe traceId
    (event.obj("timestamp").str) shouldBe now
    (event.obj("body")("output")("result").str) shouldBe """{"result": 3}"""
    (event.obj("body")("metadata")("role").str) shouldBe "tool"
    (event.obj("body")("metadata")("toolCallId").str) shouldBe "id1"
  }

  it should "handle empty content in messages" in {
    val config       = stub[ConfigReader]
    val mockTracing  = new MockLangfuseTracing(config)
    val assistantMsg = AssistantMessage(None, Seq.empty)
    val messages     = Seq(assistantMsg)
    val now          = "2024-01-01T00:00:00Z"
    val traceId      = "trace-123"

    val event = mockTracing.testCreateEvent(assistantMsg, messages, 0, now, traceId, "gpt-4")

    (event.obj("type").str) shouldBe "generation-create"
    (event.obj("body")("output")("content").str) shouldBe ""
    (event.obj("body")("output")("role").str) shouldBe "assistant"
  }
}

// Update MockLangfuseTracing to expose createEvent for testing
private class MockLangfuseTracing(configReader: ConfigReader)
    extends LangfuseTracing(
      configReader
    ) {
  def testCreateEvent(
    msg: Message,
    messages: Seq[Message],
    idx: Int,
    now: String,
    traceId: String,
    modelName: String
  ): ujson.Obj = {
    val event = createEvent(msg, messages, idx, now, traceId, modelName)
    event
  }
}
