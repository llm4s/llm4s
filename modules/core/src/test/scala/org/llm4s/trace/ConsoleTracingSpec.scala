package org.llm4s.trace

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import scala.concurrent.duration.*
import java.time.Instant

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
      created = Instant.now(),
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
      created = Instant.now(),
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
      created = Instant.now(),
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
      created = Instant.EPOCH,
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

  "What ConsoleTracing prints" should "show the query and tools for AgentInitialized, with captureContent" in {
    val output = printed {
      new ConsoleTracing(captureContent = true).traceEvent(
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
      new ConsoleTracing(captureContent = true)
        .traceEvent(TraceEvent.ToolExecuted("echo", """{"message":"hi"}""", "hi", 42.millis, false))
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
      val tracing = new ConsoleTracing(captureContent = true)
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
      created = Instant.EPOCH,
      content = "Calling echo",
      model = "model-in-completion",
      message = AssistantMessage("Calling echo", Seq(toolCall, toolCall.copy(id = "call-2")))
    )

    val output = printed(new ConsoleTracing(captureContent = true).traceCompletion(completion, "model-from-caller"))

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
    val output =
      printed(new ConsoleTracing(captureContent = true).traceError(new IllegalStateException("boom"), "agent step"))

    output should include("ERROR OCCURRED")
    output should include("Type: IllegalStateException")
    output should include("Message: boom")
    output should include("Context: agent step")
  }

  // ==========================================================================
  // Content is withheld by default (#2311)
  // ==========================================================================

  private def secret = "SECRET-PII-4111"

  "ConsoleTracing by default" should "withhold the query of AgentInitialized but show its tools" in {
    val output = printed(new ConsoleTracing().traceEvent(TraceEvent.AgentInitialized(secret, Vector("calculator"))))

    output should include("--- AGENT INITIALIZED ---")
    output should include(s"Query: [withheld: ${secret.length} chars]")
    output should include("Tools: calculator")
    (output should not).include(secret)
  }

  it should "withhold completion content but show the model, id and tool call count" in {
    val output = printed(new ConsoleTracing().traceEvent(TraceEvent.CompletionReceived("c-1", "m-1", 2, secret)))

    output should include("Model: m-1")
    output should include("ID: c-1")
    output should include("Tool Calls: 2")
    output should include(s"Content: [withheld: ${secret.length} chars]")
    (output should not).include(secret)
  }

  it should "withhold tool input and output but show the tool, outcome and duration" in {
    val output =
      printed(
        new ConsoleTracing().traceEvent(TraceEvent.ToolExecuted("echo", s"""{"q":"$secret"}""", secret, 7.millis, true))
      )

    output should include("Tool: echo")
    output should include("Success: true")
    output should include("Duration: 7ms")
    output should include("Input: [withheld:")
    output should include(s"Output: [withheld: ${secret.length} chars]")
    (output should not).include(secret)
  }

  it should "show a custom event's field names, not its values" in {
    val data   = ujson.Obj("runId" -> "run-1", "content" -> secret)
    val output = printed(new ConsoleTracing().traceEvent(TraceEvent.CustomEvent("agent.model_responded", data)))

    output should include("Name: agent.model_responded")
    output should include("Fields: runId, content")
    (output should not).include(secret)
    (output should not).include("run-1")
  }

  it should "withhold a custom event's data that is not an object" in {
    val output = printed(new ConsoleTracing().traceEvent(TraceEvent.CustomEvent("note", ujson.Str(secret))))

    output should include("Data: [withheld]")
    (output should not).include(secret)
  }

  it should "show a custom event's data with captureContent" in {
    val output =
      printed(new ConsoleTracing(captureContent = true).traceEvent(TraceEvent.CustomEvent("note", ujson.Str(secret))))

    output should include(secret)
  }

  it should "withhold an error's message and context but show its type" in {
    val output = printed(new ConsoleTracing().traceError(new IllegalStateException(secret), s"step for $secret"))

    output should include("Type: IllegalStateException")
    output should include(s"Message: [withheld: ${secret.length} chars]")
    output should include("Context: [withheld:")
    (output should not).include(secret)
  }

  it should "withhold an error with no message without failing" in {
    val output = printed(new ConsoleTracing().traceError(new IllegalStateException(), ""))

    output should include("Message: [withheld: 4 chars]")
  }

  it should "withhold an image generation error message" in {
    val event = TraceEvent.ImageGenerationCompleted(
      model = "img-1",
      provider = "p",
      operation = "generate",
      imageCount = 0,
      size = "1024x1024",
      quality = "standard",
      duration = 5.millis,
      success = false,
      errorMessage = Some(secret)
    )
    val withheld = printed(new ConsoleTracing().traceEvent(event))
    withheld should include(s"Error: [withheld: ${secret.length} chars]")
    (withheld should not).include(secret)

    printed(new ConsoleTracing(captureContent = true).traceEvent(event)) should include(s"Error: $secret")
  }

  it should "report its setting" in {
    new ConsoleTracing().captureContent shouldBe false
    new ConsoleTracing(captureContent = true).captureContent shouldBe true
  }

  "ConsoleTracing.fromExtras" should "read captureContent from the llm4s.tracing.console block" in {
    ConsoleTracing.fromExtras(Map.empty).map(_.captureContent) shouldBe Right(false)
    ConsoleTracing.fromExtras(Map("captureContent" -> "false")).map(_.captureContent) shouldBe Right(false)
    ConsoleTracing.fromExtras(Map("captureContent" -> " TRUE ")).map(_.captureContent) shouldBe Right(true)
  }

  it should "reject a value that is not a boolean" in {
    val error = ConsoleTracing.fromExtras(Map("captureContent" -> "yes")).left.map(_.message)
    error.isLeft shouldBe true
    error.left.toOption.get should include("llm4s.tracing.console.captureContent must be true or false")
  }
}
