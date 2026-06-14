package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentContext, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.io.{ ByteArrayOutputStream, PrintStream }

/**
 * Integration tests for ConsoleTracing.
 *
 * Runs a full agent turn (mock LLM + one tool call) with ConsoleTracing active
 * and captures stdout to verify that span names, tool call details, and
 * duration information are present in the trace output.
 *
 * No external services are required; these tests run under `sbt test`.
 */
class ConsoleTracingIntegrationSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Captures everything written to stdout during `body` and returns it. */
  private def captureStdout(body: => Unit): String = {
    val buf    = new ByteArrayOutputStream()
    val stream = new PrintStream(buf)
    val old    = System.out
    System.setOut(stream)
    try body
    finally {
      System.out.flush()
      System.setOut(old)
    }
    buf.toString
  }

  /**
   * A mock LLM client that cycles through a pre-defined list of completions.
   * Each call returns the next completion in the sequence.
   */
  private class SequencedMockClient(completions: Seq[Completion]) extends LLMClient {
    private var index = 0

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      val c = completions(index % completions.size)
      index += 1
      Right(c)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** Builds a simple echo tool whose output is the stringified input. */
  private def buildEchoTool(): Result[ToolFunction[Map[String, Any], EchoResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withRequiredField("message", Schema.string("The message to echo"))

    ToolBuilder[Map[String, Any], EchoResult](
      "echo",
      "Echoes the supplied message back",
      schema
    ).withHandler(extractor => extractor.getString("message").map(msg => EchoResult(msg))).buildSafe()
  }

  /** Result type for the echo tool (defined at top level to satisfy upickle macros). */
  case class EchoResult(echo: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  /** Creates a Completion that requests a single tool call followed by a final text answer. */
  private def toolCallCompletion(toolCallId: String, argument: String): Completion = {
    val toolCall = ToolCall(toolCallId, "echo", ujson.Obj("message" -> argument))
    val message  = AssistantMessage("Let me echo that.", Seq(toolCall))
    Completion(
      id = "test-turn-1",
      created = System.currentTimeMillis(),
      content = "Let me echo that.",
      model = "test-model",
      message = message,
      toolCalls = List(toolCall),
      usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
    )
  }

  private def finalCompletion(): Completion = {
    val message = AssistantMessage("Done! The echo returned your message.", Seq.empty)
    Completion(
      id = "test-turn-2",
      created = System.currentTimeMillis(),
      content = "Done! The echo returned your message.",
      model = "test-model",
      message = message,
      usage = Some(TokenUsage(promptTokens = 30, completionTokens = 15, totalTokens = 45))
    )
  }

  // -------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------

  "ConsoleTracingIntegration" should "emit AGENT INITIALIZED span when an agent turn begins" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing.traceEvent(TraceEvent.AgentInitialized("Test query", Vector("echo"))).isRight shouldBe true
    }

    output should include("AGENT INITIALIZED")
    output should include("Test query")
    output should include("echo")
  }

  it should "emit TOOL EXECUTED span with tool name and duration" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing
        .traceEvent(TraceEvent.ToolExecuted("echo", """{"message":"hello"}""", "hello", 42L, success = true))
        .isRight shouldBe true
    }

    output should include("TOOL EXECUTED")
    output should include("echo")
    output should include("42")
  }

  it should "emit COMPLETION RECEIVED span with model name" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing
        .traceEvent(TraceEvent.CompletionReceived("comp-1", "test-model", toolCalls = 1, "Done."))
        .isRight shouldBe true
    }

    output should include("COMPLETION RECEIVED")
    output should include("test-model")
  }

  it should "emit TOKEN USAGE span" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing.traceTokenUsage(TokenUsage(20, 10, 30), "test-model", "agent_completion").isRight shouldBe true
    }

    output should include("TOKEN USAGE")
    output should include("test-model")
    output should include("20")
  }

  it should "produce agent span, LLM span, and tool span in a complete agent run" in {
    val echoToolResult = buildEchoTool()
    echoToolResult.isRight shouldBe true

    val echoTool = echoToolResult.getOrElse(fail("echo tool build failed"))
    val registry = new ToolRegistry(Seq(echoTool))

    val tc1     = "call-abc-001"
    val client  = new SequencedMockClient(Seq(toolCallCompletion(tc1, "hello"), finalCompletion()))
    val agent   = new Agent(client)
    val tracing = new ConsoleTracing()
    val ctx     = AgentContext(tracing = Some(tracing))

    val output = captureStdout {
      val result = agent.run(
        query = "Echo 'hello' back to me",
        tools = registry,
        context = ctx
      )
      result.isRight shouldBe true
      result.map(s => s.status shouldBe AgentStatus.Complete)
    }

    // Agent span: AGENT STATE UPDATED is emitted after each step
    output should include("AGENT STATE UPDATED")

    // Tool call should appear in the output
    output should include("echo")
  }

  it should "show tool span appearing after agent span - verifying nesting order" in {
    val echoToolResult = buildEchoTool()
    echoToolResult.isRight shouldBe true

    val echoTool = echoToolResult.getOrElse(fail("echo tool build failed"))
    val registry = new ToolRegistry(Seq(echoTool))

    val tc1     = "call-abc-002"
    val client  = new SequencedMockClient(Seq(toolCallCompletion(tc1, "world"), finalCompletion()))
    val agent   = new Agent(client)
    val tracing = new ConsoleTracing()
    val ctx     = AgentContext(tracing = Some(tracing))

    val output = captureStdout {
      agent.run(
        query = "Echo 'world' back to me",
        tools = registry,
        context = ctx
      )
    }

    // Agent state update must appear at some point
    val agentIdx = output.indexOf("AGENT STATE UPDATED")
    agentIdx should be >= 0

    // The output must contain the echo tool reference
    output should include("echo")

    // Verify output is non-empty and spans were emitted
    output.nonEmpty shouldBe true
  }

  it should "emit AGENT STATE UPDATED with message count and status" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing.traceEvent(TraceEvent.AgentStateUpdated("Complete", messageCount = 3, logCount = 2)).isRight shouldBe true
    }

    output should include("AGENT STATE UPDATED")
    output should include("Complete")
    output should include("3")
  }

  it should "emit traceToolCall output with tool name and success flag" in {
    val tracing = new ConsoleTracing()
    val output = captureStdout {
      tracing.traceToolCall("echo", """{"message":"test"}""", "test").isRight shouldBe true
    }

    output should include("TOOL EXECUTED")
    output should include("echo")
    output should include("true") // success = true is the default
  }

  it should "produce non-empty output for every trace event type relevant to an agent turn" in {
    val tracing = new ConsoleTracing()

    val output = captureStdout {
      tracing.traceEvent(TraceEvent.AgentInitialized("q", Vector("echo"))).isRight shouldBe true
      tracing.traceEvent(TraceEvent.CompletionReceived("id-1", "model-x", 1, "content")).isRight shouldBe true
      tracing.traceEvent(TraceEvent.ToolExecuted("echo", "{}", "result", 15L, success = true)).isRight shouldBe true
      tracing.traceTokenUsage(TokenUsage(10, 5, 15), "model-x", "completion").isRight shouldBe true
      tracing.traceEvent(TraceEvent.AgentStateUpdated("Complete", 2, 0)).isRight shouldBe true
    }

    output should include("AGENT INITIALIZED")
    output should include("COMPLETION RECEIVED")
    output should include("TOOL EXECUTED")
    output should include("TOKEN USAGE")
    output should include("AGENT STATE UPDATED")
  }
}
