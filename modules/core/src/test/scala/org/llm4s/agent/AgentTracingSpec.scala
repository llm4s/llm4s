package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.trace.Tracing
import org.llm4s.trace.TraceEvent
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Focused tests that verify Agent integrates with the Tracing API
 * without changing core control flow.
 */
class AgentTracingSpec extends AnyFlatSpec with Matchers {

  // Simple recording tracer for assertions
  private class RecordingTracing extends Tracing {
    var completions: Vector[Completion]                   = Vector.empty
    var tokenUsages: Vector[(TokenUsage, String, String)] = Vector.empty
    var toolCalls: Vector[(String, String, String)]       = Vector.empty
    var states: Vector[AgentState]                        = Vector.empty
    var errors: Vector[(Throwable, String)]               = Vector.empty
    var events: Vector[TraceEvent]                        = Vector.empty

    override def traceEvent(event: TraceEvent): Result[Unit] = {
      events = events :+ event
      Right(())
    }

    override def traceAgentState(state: AgentState): Result[Unit] = {
      states = states :+ state
      Right(())
    }

    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
      toolCalls = toolCalls :+ ((toolName, input, output))
      Right(())
    }

    override def traceError(error: Throwable, context: String): Result[Unit] = {
      errors = errors :+ ((error, context))
      Right(())
    }

    override def traceCompletion(completion: Completion, model: String): Result[Unit] = {
      completions = completions :+ completion
      Right(())
    }

    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
      tokenUsages = tokenUsages :+ ((usage, model, operation))
      Right(())
    }
  }

  // Minimal LLM client that returns a single configured completion
  private class StubLLMClient(response: Result[Completion]) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      response

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      response

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private case class CalculatorResult(result: Double)
  private object CalculatorResult {
    implicit val rw: ReadWriter[CalculatorResult] = macroRW
  }

  private def createCalculatorTool(): ToolFunction[Map[String, Any], CalculatorResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Calculator parameters")
      .withRequiredField("a", Schema.number("First number"))
      .withRequiredField("b", Schema.number("Second number"))

    ToolBuilder[Map[String, Any], CalculatorResult](
      "calculator",
      "Performs basic arithmetic",
      schema
    ).withHandler { extractor =>
      for {
        a <- extractor.getDouble("a")
        b <- extractor.getDouble("b")
      } yield CalculatorResult(a + b)
    }.build()
  }

  private def createCompletion(
    content: String,
    toolCalls: Seq[ToolCall] = Seq.empty
  ): Completion = {
    val message = AssistantMessage(content, toolCalls)
    Completion(
      id = "test-completion",
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = message,
      toolCalls = toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  private def createToolCall(name: String, arguments: String, id: String = "call_123"): ToolCall =
    ToolCall(id = id, name = name, arguments = ujson.read(arguments))

  "Agent.run" should "trace completion, token usage and agent state" in {
    val completion = createCompletion("Hello, world!")
    val client     = new StubLLMClient(Right(completion))
    val agent      = new Agent(client)

    val tools   = new ToolRegistry(Seq.empty)
    val tracing = new RecordingTracing()
    val result  = agent.run("test query", tools, tracing = Some(tracing))

    result.isRight shouldBe true
    tracing.completions should have size 1
    tracing.tokenUsages should have size 1
    tracing.states.nonEmpty shouldBe true
  }

  it should "trace tool executions when tools are called" in {
    val toolCall = createToolCall("calculator", """{"a": 1, "b": 2}""")
    val completionWithTool = createCompletion(
      content = "",
      toolCalls = Seq(toolCall)
    )

    val client  = new StubLLMClient(Right(completionWithTool))
    val agent   = new Agent(client)
    val tools   = new ToolRegistry(Seq(createCalculatorTool()))
    val tracing = new RecordingTracing()
    val result  = agent.run("use calculator", tools, tracing = Some(tracing))

    result.isRight shouldBe true
    tracing.toolCalls.nonEmpty shouldBe true
  }
}
