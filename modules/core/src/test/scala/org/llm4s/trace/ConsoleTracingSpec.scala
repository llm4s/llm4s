package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentContext, AgentState, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.io.ByteArrayOutputStream

/**
 * Tests for ConsoleTracing.
 *
 * The first sections check that each call succeeds. "What ConsoleTracing prints" captures stdout
 * and checks the content: each event's header and fields, truncation, and the order of the
 * events a whole agent run with a tool call prints (#1003). Based on the console suite in #1035
 * by @kannupriyakalra, deduplicated against the cases above.
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

    noException should be thrownBy tracing.traceEvent(state.toTraceEvent)
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

    noException should be thrownBy tracing.traceEvent(state.toTraceEvent)
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

    noException should be thrownBy tracing.traceEvent(state.toTraceEvent)
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

    noException should be thrownBy tracing.traceEvent(state.toTraceEvent)
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

    noException should be thrownBy tracing.traceEvent(state.toTraceEvent)
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
      new ConsoleTracing().traceEvent(TraceEvent.ToolExecuted("echo", """{"message":"hi"}""", "hi", 42L, false))
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

  it should "show the status and counts of the AgentStateUpdated built by AgentState#toTraceEvent" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("hi"), AssistantMessage("hello"), UserMessage("bye"))),
      tools = ToolRegistry.empty,
      status = AgentStatus.Failed("tool crashed"),
      logs = Vector("one", "two")
    )

    val output = printed(new ConsoleTracing().traceEvent(state.toTraceEvent))

    output should include("--- AGENT STATE UPDATED ---")
    output should include("Status: Failed(tool crashed)")
    output should include("Messages: 3")
    output should include("Logs: 2")
  }

  it should "print an agent run with a tool call in the order it happened" in {
    val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
    val client = new SequencedClient(
      Seq(
        Completion("turn-1", 0L, "", "test-model", AssistantMessage("", Seq(toolCall)), List(toolCall), usage1),
        Completion("turn-2", 0L, "Echoed.", "test-model", AssistantMessage("Echoed."), usage = usage2)
      )
    )

    var result: Result[AgentState] = Right(AgentState(Conversation(Seq.empty), ToolRegistry.empty))
    val output = printed {
      result = echoTool.flatMap { tool =>
        new Agent(client)
          .run("Echo hello", new ToolRegistry(Seq(tool)), context = AgentContext(tracing = Some(new ConsoleTracing())))
      }
    }

    result.map(_.status) shouldBe Right(AgentStatus.Complete)

    val firstCompletion  = output.indexOf("ID: turn-1")
    val tool             = output.indexOf("Tool: echo")
    val secondCompletion = output.indexOf("ID: turn-2")
    val lastState        = output.lastIndexOf("--- AGENT STATE UPDATED ---")

    Seq(firstCompletion, tool, secondCompletion, lastState).foreach(_ should be >= 0)
    firstCompletion should be < tool       // the model asks for the tool...
    tool should be < secondCompletion      // ...the tool runs before the model is called again...
    secondCompletion should be < lastState // ...and the run ends with its final state.
    output should include("""Input: {"message":"hello"}""")
    output should include("Prompt Tokens: 20")
    output.substring(lastState) should include("Status: Complete")
  }

  private def usage1 = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
  private def usage2 = Some(TokenUsage(promptTokens = 30, completionTokens = 5, totalTokens = 35))

  /** Returns each completion in turn, then the last one again. */
  private class SequencedClient(completions: Seq[Completion]) extends LLMClient {
    private var calls = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val completion = completions(calls.min(completions.size - 1))
      calls += 1
      Right(completion)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe()
}
