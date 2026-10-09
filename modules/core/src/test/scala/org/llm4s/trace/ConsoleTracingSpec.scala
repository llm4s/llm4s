package org.llm4s.trace

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import scala.concurrent.duration.*

/**
 * Tests for ConsoleTracing.
 *
 * The first sections check that each call succeeds. "What ConsoleTracing prints" captures stdout
 * and checks the content: each event's header and fields, and truncation (#1003). Based on the
 * console suite in #1035 by @kannupriyakalra, deduplicated against the cases above.
 *
 * What an agent run prints - its `AgentRunEnded`, and the order of the events a whole run
 * with a tool call produces - is in `llm4s-agent`'s `AgentRunTracingSpec`, with the agent
 * runtime (#1242).
 */
class ConsoleTracingSpec extends AnyFlatSpec with Matchers {

  private def ended(status: String, messages: Seq[Message]): TraceEvent.AgentRunEnded =
    TraceEvent.AgentRunEnded("thread-1", "run-1", "assistant", status, messages, UsageSummary())

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
  // Agent Run Tracing
  // ==========================================================================

  it should "trace agent state with minimal configuration" in {
    val tracing = new ConsoleTracing()
    val event   = ended("completed", Seq.empty)

    noException should be thrownBy tracing.traceEvent(event)
  }

  it should "trace agent state with full configuration" in {
    val tracing = new ConsoleTracing()
    val messages = Seq(
      UserMessage("Hello, how are you?"),
      AssistantMessage(Some("I'm doing well, thanks!"), Seq.empty),
      UserMessage("Great to hear!")
    )
    val event = ended("completed", messages)

    noException should be thrownBy tracing.traceEvent(event)
  }

  it should "trace agent state with system message" in {
    val tracing  = new ConsoleTracing()
    val messages = Seq(SystemMessage("You are a helpful assistant"), UserMessage("Hello"))
    val event    = ended("suspended", messages)

    noException should be thrownBy tracing.traceEvent(event)
  }

  it should "trace agent state with assistant tool calls" in {
    val tracing  = new ConsoleTracing()
    val toolCall = ToolCall("call-123", "calculator", ujson.Obj("a" -> 1, "b" -> 2))
    val messages = Seq(
      UserMessage("Calculate 1+2"),
      AssistantMessage(Some("Let me calculate that."), Seq(toolCall)),
      ToolMessage("3", "call-123")
    )
    val event = ended("completed", messages)

    noException should be thrownBy tracing.traceEvent(event)
  }

  it should "trace an agent run that ended with usage" in {
    val tracing = new ConsoleTracing()
    val event   = ended("failed", Seq.empty).copy(usage = UsageSummary().add("m", TokenUsage(1, 2, 3), None))

    noException should be thrownBy tracing.traceEvent(event)
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

  // ==========================================================================
  // What ConsoleTracing prints (#1003)
  // ==========================================================================

  /** Runs `body` with Scala's stdout captured, and returns what it printed without ANSI colours. */
  private def printed(body: => Unit): String = {
    val out = new ByteArrayOutputStream()
    Console.withOut(out)(body)
    out.toString.replaceAll("\u001b\\[[0-9;]*m", "")
  }

  "What ConsoleTracing prints" should "show the query and tools for AgentInitialized" in {
    val output = printed {
      new ConsoleTracing().traceEvent(
        TraceEvent.AgentInitialized("What is 2 + 3?", Vector("calculator", "echo"))
      ) shouldBe
        Right(())
    }

    output should include("--- AGENT INITIALIZED ---")
    output should include("Query: What is 2 + 3?")
    output should include("Tools: calculator, echo")
  }

  it should "show the tool, its outcome and its duration for ToolExecuted" in {
    val output = printed {
      new ConsoleTracing().traceEvent(TraceEvent.ToolExecuted("echo", """{"message":"hi"}""", "hi", 42.millis, false))
    }

    output should include("--- TOOL EXECUTED ---")
    output should include("Tool: echo")
    output should include("Success: false")
    output should include("Duration: 42ms")
    output should include("""Input: {"message":"hi"}""")
    output should include("Output: hi")
  }

  it should "show traceToolCall as a successful ToolExecuted with no duration" in {
    val output = printed(new ConsoleTracing().traceToolCall("echo", "{}", "ok"))

    output should include("--- TOOL EXECUTED ---")
    output should include("Success: true")
    output should include("Duration: 0ms")
  }

  it should "truncate tool input at 100 characters and completion content at 200" in {
    val output = printed {
      val tracing = new ConsoleTracing()
      tracing.traceToolCall("tool", "i" * 150, "o" * 150)
      tracing.traceEvent(TraceEvent.CompletionReceived("c-1", "m", 0, "c" * 250))
    }

    output should include("Input: " + "i" * 100 + "...")
    (output should not).include("i" * 101)
    output should include("Output: " + "o" * 100 + "...")
    output should include("Content: " + "c" * 200 + "...")
    (output should not).include("c" * 201)
  }

  it should "show the model named by the caller and the tool call count for traceCompletion" in {
    val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hi"))
    val completion = Completion(
      id = "completion-7",
      created = 0L,
      content = "Calling echo",
      model = "model-in-completion",
      message = AssistantMessage("Calling echo", Seq(toolCall, toolCall.copy(id = "call-2")))
    )

    val output = printed(new ConsoleTracing().traceCompletion(completion, "model-from-caller"))

    output should include("COMPLETION RECEIVED")
    output should include("Model: model-from-caller")
    (output should not).include("model-in-completion")
    output should include("ID: completion-7")
    output should include("Tool Calls: 2")
    output should include("Content: Calling echo")
  }

  it should "show every count for TokenUsage" in {
    val output = printed(new ConsoleTracing().traceTokenUsage(TokenUsage(20, 10, 30), "test-model", "agent_completion"))

    output should include("--- TOKEN USAGE ---")
    output should include("Model: test-model")
    output should include("Operation: agent_completion")
    output should include("Prompt Tokens: 20")
    output should include("Completion Tokens: 10")
    output should include("Total Tokens: 30")
  }

  it should "show the error's type, message and context for traceError" in {
    val output = printed(new ConsoleTracing().traceError(new IllegalStateException("boom"), "agent step"))

    output should include("ERROR OCCURRED")
    output should include("Type: IllegalStateException")
    output should include("Message: boom")
    output should include("Context: agent step")
  }
}
