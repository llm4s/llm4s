package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.error.{ NetworkError, UnknownError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList }
import scala.jdk.CollectionConverters._

/**
 * How an agent run reaches the tracing contract: `withTracing` traces each run's durable events as
 * `graph.*` custom events, complete by the time the run returns, and only that run's. The agent
 * produces no other trace event; core's tracing specs build the agent-state event directly.
 */
class AgentRunTracingSpec extends AnyFlatSpec with Matchers {

  /** Records every event; with `failing`, reports each as a failure, which must not fail the run. */
  final private class Recording(failing: Boolean = false) extends Tracing {
    private val events = new ConcurrentLinkedQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = {
      events.add(event)
      if (failing) Left(UnknownError("tracing down", new RuntimeException("x"))) else Right(())
    }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())

    def all: Vector[TraceEvent]                = events.asScala.toVector
    def custom: Vector[TraceEvent.CustomEvent] = all.collect { case c: TraceEvent.CustomEvent => c }
    def names: Vector[String]                  = custom.map(_.name)
  }

  /** Answers call N with `responses(N)`. */
  final private class Scripted(responses: Result[Completion]*) extends LLMClient {
    private val sent = new CopyOnWriteArrayList[Conversation]()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val index = sent.size
      sent.add(conversation)
      responses(index)
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def answer(text: String): Completion =
    Completion("answer", 0L, text, "test-model", AssistantMessage(text), usage = usage)

  private val toolCall = ToolCall("call-1", "echo", ujson.Obj("message" -> "hello"))
  private def toolCallCompletion: Completion =
    Completion("turn-1", 0L, "", "test-model", AssistantMessage(None, Seq(toolCall)), List(toolCall), usage)

  private def usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))

  private case class EchoResult(echo: String)
  private object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "Echoes the supplied message back",
    Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("message", Schema.string("The message"))
  ).withHandler(_.getString("message").map(EchoResult(_))).buildSafe().fold(e => fail(e.formatted), identity)

  private def traced(client: LLMClient, tracing: Tracing): Agent =
    Agent
      .builder("assistant", client)
      .withTools(new ToolRegistry(Seq(echoTool)))
      .withTracing(tracing)
      .build()
      .fold(e => fail(e.message), identity)

  private def ok(result: Result[AgentResult]): AgentResult = result.fold(e => fail(e.message), identity)

  "An agent with tracing" should "trace the whole run as graph events, complete when the run returns" in {
    val tracing = new Recording()
    val result  = ok(traced(new Scripted(Right(answer("hello"))), tracing).run("hi"))

    val names = tracing.names
    names.head shouldBe "graph.run_started"
    names.last shouldBe "graph.run_completed"
    names should contain("graph.task_completed")
    names should contain("graph.checkpoint_committed")
    tracing.custom.map(_.data("runId").str).toSet shouldBe Set(result.runId.value)
    tracing.custom.map(_.data("threadId").str).toSet shouldBe Set(result.threadId.value)
    tracing.custom.map(_.data("seq").num) shouldBe sorted
  }

  it should "trace a tool call's task between the model calls around it" in {
    val tracing = new Recording()
    ok(traced(new Scripted(Right(toolCallCompletion), Right(answer("Echoed."))), tracing).run("Echo hello"))

    val completedNodes = tracing.custom
      .filter(_.name == "graph.task_completed")
      .map(_.data("nodeId").str)
    completedNodes shouldBe Vector(
      "input",
      "assistant/model",
      "assistant/call-tool",
      "assistant/collect",
      "assistant/model",
      "assistant/finish"
    )
  }

  it should "trace each run on a thread once: a later run's events are its own" in {
    val tracing = new Recording()
    val agent   = traced(new Scripted(Right(answer("one")), Right(answer("two"))), tracing)
    val thread  = ThreadId("traced-thread")

    val first  = ok(agent.run(thread, "first"))
    val before = tracing.custom.size
    val second = ok(agent.run(thread, "second"))

    val later = tracing.custom.drop(before)
    later.map(_.data("runId").str).toSet shouldBe Set(second.runId.value)
    later.head.name shouldBe "graph.run_started"
    later.last.name shouldBe "graph.run_completed"
    tracing.custom.count(_.data("runId").str == first.runId.value) shouldBe before
  }

  it should "trace a failed run's failure" in {
    val tracing = new Recording()
    val result  = traced(new Scripted(Left(NetworkError("down", None, "mock://llm"))), tracing).run("hi")

    result.isLeft shouldBe true
    tracing.names should contain("graph.task_failed")
    tracing.names.last shouldBe "graph.run_failed"
  }

  it should "complete the run when the tracer itself fails" in {
    val tracing = new Recording(failing = true)
    val result  = ok(traced(new Scripted(Right(answer("fine"))), tracing).run("hi"))

    result.answer shouldBe Some("fine")
    tracing.names.last shouldBe "graph.run_completed"
  }

  it should "trace nothing but graph custom events" in {
    val tracing = new Recording()
    ok(traced(new Scripted(Right(answer("hello"))), tracing).run("hi"))

    tracing.custom.size should be > 0
    tracing.all.filterNot(_.isInstanceOf[TraceEvent.CustomEvent]) shouldBe empty
    tracing.names.foreach(_ should startWith("graph."))
  }
}
