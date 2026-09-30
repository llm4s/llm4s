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
 * How the agent runtime reaches the tracing contract: `AgentState#toTraceEvent`, and what a whole
 * agent run prints through `ConsoleTracing`.
 *
 * These cases were in core's `ConsoleTracingSpec` and `NoOpTracingSpec`. They need `AgentState`
 * and `Agent`, which moved to `llm4s-agent` (#1242); the core specs now build
 * `TraceEvent.AgentStateUpdated` directly, as the contract sees it.
 */
class AgentRunTracingSpec extends AnyFlatSpec with Matchers {

  private val toolCall = ToolCall("call-1", "calculator", ujson.Obj("a" -> 1, "b" -> 2))

  /** An agent state with every kind of message in it, as an agent run leaves it. */
  private val agentState = AgentState(
    conversation = Conversation(
      Seq(
        SystemMessage("You are a calculator."),
        UserMessage("What is 1 + 2?"),
        AssistantMessage(None, Seq(toolCall)),
        ToolMessage("""{"result":3}""", "call-1"),
        AssistantMessage("3")
      )
    ),
    tools = ToolRegistry.empty,
    initialQuery = Some("What is 1 + 2?"),
    status = AgentStatus.Complete,
    logs = Vector("[tool] calculator", "[assistant] 3")
  )

  "AgentState#toTraceEvent" should "carry the status, the message and log counts, and the messages" in {
    val event = agentState.toTraceEvent

    event.status shouldBe "Complete"
    event.messageCount shouldBe 5
    event.logCount shouldBe 2
    event.messages shouldBe agentState.conversation.messages
  }

  "NoOpTracing" should "trace agent state as an ordinary event, whatever the state holds" in {
    val tracing = new NoOpTracing()
    val empty   = AgentState(Conversation(Seq.empty), ToolRegistry.empty)
    val failed  = agentState.copy(status = AgentStatus.Failed("tool crashed"))

    tracing.traceEvent(agentState.toTraceEvent) shouldBe Right(())
    tracing.traceEvent(empty.toTraceEvent) shouldBe Right(())
    tracing.traceEvent(failed.toTraceEvent) shouldBe Right(())
  }

  /** Runs `body` with Scala's stdout captured, and returns what it printed without ANSI colours. */
  private def printed(body: => Unit): String = {
    val out = new ByteArrayOutputStream()
    Console.withOut(out)(body)
    out.toString.replaceAll("\u001b\\[[0-9;]*m", "")
  }

  "ConsoleTracing" should "show the status and counts of the AgentStateUpdated built by AgentState#toTraceEvent" in {
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
