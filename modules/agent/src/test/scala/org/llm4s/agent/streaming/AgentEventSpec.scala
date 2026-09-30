package org.llm4s.agent.streaming

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AgentEventSpec extends AnyFlatSpec with Matchers {

  "AgentEvent.TextDelta" should "be created with factory method" in {
    val event = AgentEvent.textDelta("Hello")
    event.delta shouldBe "Hello"
    event.timestamp should not be null
  }

  "AgentEvent.TextComplete" should "be created with factory method" in {
    val event = AgentEvent.textComplete("Full text here")
    event.fullText shouldBe "Full text here"
    event.timestamp should not be null
  }

  "AgentEvent.ToolCallStarted" should "be created with factory method" in {
    val event = AgentEvent.toolStarted("call-123", "get_weather", """{"city": "London"}""")
    event.toolCallId shouldBe "call-123"
    event.toolName shouldBe "get_weather"
    event.arguments shouldBe """{"city": "London"}"""
    event.timestamp should not be null
  }

  "AgentEvent.ToolCallCompleted" should "be created with factory method" in {
    val event = AgentEvent.toolCompleted(
      toolCallId = "call-123",
      toolName = "get_weather",
      result = """{"temp": 20}""",
      success = true,
      durationMs = 150
    )
    event.toolCallId shouldBe "call-123"
    event.toolName shouldBe "get_weather"
    event.result shouldBe """{"temp": 20}"""
    event.success shouldBe true
    event.durationMs shouldBe 150
  }

  "AgentEvent.ToolCallFailed" should "capture error information" in {
    val timestamp = Instant.now()
    val event = AgentEvent.ToolCallFailed(
      toolCallId = "call-456",
      toolName = "bad_tool",
      error = "Tool not found",
      timestamp = timestamp
    )
    event.toolCallId shouldBe "call-456"
    event.toolName shouldBe "bad_tool"
    event.error shouldBe "Tool not found"
    event.timestamp shouldBe timestamp
  }

  "AgentEvent.AgentStarted" should "be created with factory method" in {
    val event = AgentEvent.agentStarted("What's the weather?", 3)
    event.query shouldBe "What's the weather?"
    event.toolCount shouldBe 3
    event.timestamp should not be null
  }

  "AgentEvent.StepStarted" should "be created with factory method" in {
    val event = AgentEvent.stepStarted(0)
    event.stepNumber shouldBe 0
    event.timestamp should not be null
  }

  "AgentEvent.StepCompleted" should "be created with factory method" in {
    val event = AgentEvent.stepCompleted(1, hasToolCalls = true)
    event.stepNumber shouldBe 1
    event.hasToolCalls shouldBe true
    event.timestamp should not be null
  }

  "AgentEvent.AgentFailed" should "be created with factory method" in {
    val error = org.llm4s.error.ProcessingError("test", "Test failure")
    val event = AgentEvent.agentFailed(error, Some(2))
    event.error shouldBe error
    event.stepNumber shouldBe Some(2)
    event.timestamp should not be null
  }

  it should "accept None for stepNumber" in {
    val error = org.llm4s.error.ProcessingError("test", "Test failure")
    val event = AgentEvent.agentFailed(error, None)
    event.stepNumber shouldBe None
  }

  "AgentEvent.HandoffStarted" should "capture handoff information" in {
    val timestamp = Instant.now()
    val event = AgentEvent.HandoffStarted(
      targetAgentName = "SpecialistAgent",
      reason = Some("Requires domain expertise"),
      preserveContext = true,
      timestamp = timestamp
    )
    event.targetAgentName shouldBe "SpecialistAgent"
    event.reason shouldBe Some("Requires domain expertise")
    event.preserveContext shouldBe true
  }

  "AgentEvent.HandoffCompleted" should "indicate success status" in {
    val timestamp = Instant.now()
    val event = AgentEvent.HandoffCompleted(
      targetAgentName = "SpecialistAgent",
      success = true,
      timestamp = timestamp
    )
    event.targetAgentName shouldBe "SpecialistAgent"
    event.success shouldBe true
  }

  "AgentEvent.InputGuardrailStarted" should "capture guardrail name" in {
    val timestamp = Instant.now()
    val event     = AgentEvent.InputGuardrailStarted("LengthCheck", timestamp)
    event.guardrailName shouldBe "LengthCheck"
  }

  "AgentEvent.InputGuardrailCompleted" should "indicate pass/fail" in {
    val timestamp = Instant.now()
    val event     = AgentEvent.InputGuardrailCompleted("ProfanityFilter", passed = false, timestamp)
    event.guardrailName shouldBe "ProfanityFilter"
    event.passed shouldBe false
  }

  "AgentEvent.OutputGuardrailStarted" should "capture guardrail name" in {
    val timestamp = Instant.now()
    val event     = AgentEvent.OutputGuardrailStarted("JSONValidator", timestamp)
    event.guardrailName shouldBe "JSONValidator"
  }

  "AgentEvent.OutputGuardrailCompleted" should "indicate pass/fail" in {
    val timestamp = Instant.now()
    val event     = AgentEvent.OutputGuardrailCompleted("ToneValidator", passed = true, timestamp)
    event.guardrailName shouldBe "ToneValidator"
    event.passed shouldBe true
  }

  "All AgentEvent types" should "have timestamps" in {
    val events: Seq[AgentEvent] = Seq(
      AgentEvent.textDelta("delta"),
      AgentEvent.textComplete("complete"),
      AgentEvent.toolStarted("id", "name", "{}"),
      AgentEvent.toolCompleted("id", "name", "result", success = true, 100),
      AgentEvent.ToolCallFailed("id", "name", "error", Instant.now()),
      AgentEvent.agentStarted("query", 1),
      AgentEvent.stepStarted(0),
      AgentEvent.stepCompleted(0, hasToolCalls = false),
      AgentEvent.agentFailed(org.llm4s.error.ProcessingError("op", "msg"), None),
      AgentEvent.HandoffStarted("target", None, true, Instant.now()),
      AgentEvent.HandoffCompleted("target", true, Instant.now()),
      AgentEvent.InputGuardrailStarted("name", Instant.now()),
      AgentEvent.InputGuardrailCompleted("name", passed = true, Instant.now()),
      AgentEvent.OutputGuardrailStarted("name", Instant.now()),
      AgentEvent.OutputGuardrailCompleted("name", passed = true, Instant.now())
    )

    events.foreach(event => event.timestamp should not be null)
  }

  "Event timestamps" should "be ordered when created sequentially" in {
    val event1 = AgentEvent.agentStarted("query", 1)
    Thread.sleep(1)
    val event2 = AgentEvent.stepStarted(0)
    Thread.sleep(1)
    val event3 = AgentEvent.stepCompleted(0, hasToolCalls = false)

    event1.timestamp.isBefore(event2.timestamp) || event1.timestamp == event2.timestamp shouldBe true
    event2.timestamp.isBefore(event3.timestamp) || event2.timestamp == event3.timestamp shouldBe true
  }
}
