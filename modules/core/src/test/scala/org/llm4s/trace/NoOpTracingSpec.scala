package org.llm4s.trace

import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Conversation, TokenUsage }
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NoOpTracingSpec extends AnyFlatSpec with Matchers {

  "NoOpTracing" should "return success when tracing an event" in {
    val tracing = new NoOpTracing()
    val event   = TraceEvent.CustomEvent("test-event", ujson.Obj("key" -> "value"))

    tracing.traceEvent(event) shouldBe Right(())
  }

  it should "return success when tracing agent state" in {
    val tracing = new NoOpTracing()
    val state = AgentState(
      conversation = Conversation(Seq.empty),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )

    tracing.traceAgentState(state) shouldBe Right(())
  }

  it should "return success when tracing a tool call" in {
    val tracing = new NoOpTracing()

    tracing.traceToolCall("calculator", """{"a":1,"b":2}""", "3") shouldBe Right(())
  }

  it should "return success when tracing an error" in {
    val tracing = new NoOpTracing()
    val error   = new RuntimeException("test error")

    tracing.traceError(error, "test context") shouldBe Right(())
  }

  it should "return success when tracing a completion" in {
    val tracing = new NoOpTracing()
    val completion = Completion(
      id = "completion-1",
      created = 0L,
      content = "Test response",
      model = "test-model",
      message = AssistantMessage(Some("Test response"), Seq.empty),
      usage = Some(TokenUsage(2, 3, 5))
    )

    tracing.traceCompletion(completion, "test-model") shouldBe Right(())
  }

  it should "return success when tracing token usage" in {
    val tracing = new NoOpTracing()
    val usage   = TokenUsage(10, 5, 15)

    tracing.traceTokenUsage(usage, "test-model", "completion") shouldBe Right(())
  }

  it should "handle repeated calls without failing" in {
    val tracing = new NoOpTracing()

    (1 to 1000).foreach { index =>
      val event = TraceEvent.CustomEvent(s"event-$index", ujson.Obj())
      tracing.traceEvent(event) shouldBe Right(())
    }
  }

  it should "shut down without throwing" in {
    val tracing = new NoOpTracing()

    noException should be thrownBy tracing.shutdown()
  }
}
