package org.llm4s.trace

import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for ConsoleTracing implementation (formerly ConsoleTracing).
 */
class ConsoleTracingSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Basic Trace Operations
  // ==========================================================================

  "ConsoleTracing" should "trace events without throwing" in {
    val tracing = new ConsoleTracing()

    noException should be thrownBy tracing.traceEvent("Test event occurred")
  }

  it should "trace tool calls without throwing" in {
    val tracing = new ConsoleTracing()

    noException should be thrownBy tracing.traceToolCall(
      "calculator",
      """{"operation": "add", "a": 1, "b": 2}""",
      "3"
    )
  }

  it should "trace errors without throwing" in {
    val tracing = new ConsoleTracing()
    val error   = new RuntimeException("Something went wrong")

    noException should be thrownBy tracing.traceError(error)
  }

  it should "trace errors with nested cause" in {
    val tracing = new ConsoleTracing()
    val cause   = new IllegalArgumentException("Invalid input")
    val error   = new RuntimeException("Outer error", cause)

    noException should be thrownBy tracing.traceError(error)
  }

  // ==========================================================================
  // Agent State Tracing
  // ==========================================================================

  it should "trace agent state with minimal configuration" in {
    val tracing = new ConsoleTracing()
    val state = AgentState(
      conversation = Conversation(Seq.empty),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )

    noException should be thrownBy tracing.traceAgentState(state)
  }

  it should "trace agent state with full configuration" in {
    val tracing = new ConsoleTracing()
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Hello, how are you?"),
          AssistantMessage(Some("I'm doing well, thanks!"), Seq.empty),
          UserMessage("Great to hear!")
        )
      ),
      tools = ToolRegistry.empty,
      status = AgentStatus.Complete,
      initialQuery = Some("Test query"),
      logs = Vector("[assistant] Generated response", "[tool] Executed tool")
    )

    noException should be thrownBy tracing.traceAgentState(state)
  }

  it should "trace agent state with system message" in {
    val tracing = new ConsoleTracing()
    val state = AgentState(
      conversation = Conversation(
        Seq(
          SystemMessage("You are a helpful assistant"),
          UserMessage("Hello")
        )
      ),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )

    noException should be thrownBy tracing.traceAgentState(state)
  }

  it should "trace agent state with assistant tool calls" in {
    val tracing  = new ConsoleTracing()
    val toolCall = ToolCall("call-123", "calculator", ujson.Obj("a" -> 1, "b" -> 2))
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Calculate 1+2"),
          AssistantMessage(Some("Let me calculate that."), Seq(toolCall)),
          ToolMessage("3", "call-123")
        )
      ),
      tools = ToolRegistry.empty,
      status = AgentStatus.Complete
    )

    noException should be thrownBy tracing.traceAgentState(state)
  }

  it should "trace agent state with various log types" in {
    val tracing = new ConsoleTracing()
    val state = AgentState(
      conversation = Conversation(Seq.empty),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress,
      logs = Vector(
        "[assistant] Generated response",
        "[tool] Executed calculator",
        "[tools] Available: calculator, web_search",
        "[system] Agent initialized",
        "Unformatted log entry"
      )
    )

    noException should be thrownBy tracing.traceAgentState(state)
  }

  // ==========================================================================
  // Completion Tracing
  // ==========================================================================

  it should "trace completion with token usage" in {
    val tracing = new ConsoleTracing()
    val completion = Completion(
      id = "cmpl-123456",
      created = System.currentTimeMillis() / 1000,
      content = "Hello, I'm an AI assistant.",
      model = "gpt-4",
      message = AssistantMessage(Some("Hello, I'm an AI assistant."), Seq.empty),
      usage = Some(TokenUsage(100, 50, 150))
    )

    noException should be thrownBy tracing.traceCompletion(completion, "gpt-4")
  }

  it should "trace completion without token usage" in {
    val tracing = new ConsoleTracing()
    val completion = Completion(
      id = "cmpl-789",
      created = System.currentTimeMillis() / 1000,
      content = "Response without usage",
      model = "gpt-4",
      message = AssistantMessage(Some("Response without usage"), Seq.empty),
      usage = None
    )

    noException should be thrownBy tracing.traceCompletion(completion, "gpt-4")
  }

  it should "trace completion with tool calls" in {
    val tracing  = new ConsoleTracing()
    val toolCall = ToolCall("call-456", "web_search", ujson.Obj("query" -> "test"))
    val completion = Completion(
      id = "cmpl-tools",
      created = System.currentTimeMillis() / 1000,
      content = "I'll search for that.",
      model = "gpt-4",
      message = AssistantMessage(Some("I'll search for that."), Seq(toolCall)),
      usage = Some(TokenUsage(50, 25, 75))
    )

    noException should be thrownBy tracing.traceCompletion(completion, "gpt-4")
  }

  // ==========================================================================
  // Token Usage Tracing
  // ==========================================================================

  it should "trace token usage with non-zero values" in {
    val tracing = new ConsoleTracing()
    val usage   = TokenUsage(1000, 500, 1500)

    noException should be thrownBy tracing.traceTokenUsage(usage, "gpt-4", "completion")
  }

  it should "trace token usage with zero values" in {
    val tracing = new ConsoleTracing()
    val usage   = TokenUsage(0, 0, 0)

    noException should be thrownBy tracing.traceTokenUsage(usage, "gpt-4", "empty_request")
  }

  it should "trace token usage with large values" in {
    val tracing = new ConsoleTracing()
    val usage   = TokenUsage(100000, 50000, 150000)

    noException should be thrownBy tracing.traceTokenUsage(usage, "gpt-4-32k", "large_context")
  }

  // ==========================================================================
  // Output Formatting Tests
  // ==========================================================================

  it should "handle truncation of long JSON in tool calls" in {
    val tracing   = new ConsoleTracing()
    val longInput = "{" + "\"key\":\"value\"," * 100 + "}"

    // Should not throw even with very long input
    noException should be thrownBy tracing.traceToolCall("tool", longInput, "result")
  }

  it should "handle truncation of long content in completions" in {
    val tracing     = new ConsoleTracing()
    val longContent = "A" * 1000
    val completion = Completion(
      id = "cmpl-long",
      created = 0L,
      content = longContent,
      model = "gpt-4",
      message = AssistantMessage(Some(longContent), Seq.empty),
      usage = None
    )

    noException should be thrownBy tracing.traceCompletion(completion, "gpt-4")
  }

  it should "handle very long tool output" in {
    val tracing    = new ConsoleTracing()
    val longOutput = "Result: " + "x" * 500

    noException should be thrownBy tracing.traceToolCall("tool", "{}", longOutput)
  }

  // ==========================================================================
  // AnsiColors Integration Tests
  // ==========================================================================

  it should "use AnsiColors constants for formatting" in {
    // Verify AnsiColors is being used (integration test)
    import AnsiColors._

    RESET should not be empty
    GREEN should not be empty
    RED should not be empty
    (separator() should have).length(60)
  }
}
