package org.llm4s.agent

import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global

/** Unit tests for [[ToolProcessor]] — uses stub ToolRegistry, no LLM calls. */
class ToolProcessorSpec extends AnyFlatSpec with Matchers {

  // ── Stub tool infrastructure ──────────────────────────────────────────────────

  case class EchoResult(echo: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def echoTool(): Result[ToolFunction[Map[String, Any], EchoResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withRequiredField("message", Schema.string("Message to echo"))

    ToolBuilder[Map[String, Any], EchoResult](
      "echo",
      "Echoes the message back",
      schema
    ).withHandler(extractor => extractor.getString("message").map(m => EchoResult(m))).buildSafe()
  }

  private def failingTool(): Result[ToolFunction[Map[String, Any], EchoResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Fail parameters")
      .withRequiredField("message", Schema.string("Message"))

    ToolBuilder[Map[String, Any], EchoResult](
      "fail_tool",
      "Always fails",
      schema
    ).withHandler(_ => Left("intentional failure")).buildSafe()
  }

  private def mkEchoRegistry(): ToolRegistry =
    echoTool() match {
      case Right(t) => new ToolRegistry(Seq(t))
      case Left(e)  => fail(s"Tool creation failed: $e")
    }

  private def mkFailRegistry(): ToolRegistry =
    failingTool() match {
      case Right(t) => new ToolRegistry(Seq(t))
      case Left(e)  => fail(s"Tool creation failed: $e")
    }

  private def mkState(registry: ToolRegistry): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = registry
    )

  private def echoToolCall(message: String, id: String = "tc-1"): ToolCall =
    ToolCall(
      id = id,
      name = "echo",
      arguments = ujson.Obj("message" -> ujson.Str(message))
    )

  private def failToolCall(id: String = "tc-fail"): ToolCall =
    ToolCall(
      id = id,
      name = "fail_tool",
      arguments = ujson.Obj("message" -> ujson.Str("oops"))
    )

  private val noOpContext = AgentContext.Default

  // ── processToolCalls ──────────────────────────────────────────────────────────

  "ToolProcessor.processToolCalls" should "add a ToolMessage for a successful tool call" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("hello")

    val result       = ToolProcessor.processToolCalls(state, Seq(tc), noOpContext)
    val toolMessages = result.conversation.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 1
    toolMessages.head.content should include("hello")
  }

  it should "record a log entry for each tool call" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("world")

    val result = ToolProcessor.processToolCalls(state, Seq(tc), noOpContext)
    result.logs.exists(_.contains("echo")) shouldBe true
  }

  it should "return structured JSON error on tool failure rather than Left" in {
    val registry = mkFailRegistry()
    val state    = mkState(registry)
    val tc       = failToolCall()

    val result = ToolProcessor.processToolCalls(state, Seq(tc), noOpContext)
    // Should return Right (error captured in tool message), not Left
    val toolMessages = result.conversation.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 1
    toolMessages.head.content should include("error")
  }

  it should "include duration in log entry" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("duration-test")

    val result = ToolProcessor.processToolCalls(state, Seq(tc), noOpContext)
    result.logs.exists(l => l.contains("echo") && l.contains("ms")) shouldBe true
  }

  it should "process multiple tool calls in sequence and thread state correctly" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tcs = Seq(
      echoToolCall("first", "tc-1"),
      echoToolCall("second", "tc-2")
    )

    val result       = ToolProcessor.processToolCalls(state, tcs, noOpContext)
    val toolMessages = result.conversation.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 2
    (toolMessages.map(_.toolCallId) should contain).allOf("tc-1", "tc-2")
  }

  // ── processToolCallsWithEvents ────────────────────────────────────────────────

  "ToolProcessor.processToolCallsWithEvents" should "emit ToolCallStarted before execution" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("event-test")
    val events   = ArrayBuffer[AgentEvent]()

    ToolProcessor.processToolCallsWithEvents(state, Seq(tc), events += _, noOpContext)

    events.exists {
      case AgentEvent.ToolCallStarted(_, name, _, _) => name == "echo"
      case _                                         => false
    } shouldBe true
  }

  it should "emit ToolCallCompleted with success=true on a successful tool call" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("success-event")
    val events   = ArrayBuffer[AgentEvent]()

    ToolProcessor.processToolCallsWithEvents(state, Seq(tc), events += _, noOpContext)

    events.exists {
      case AgentEvent.ToolCallCompleted(_, name, _, true, _, _) => name == "echo"
      case _                                                    => false
    } shouldBe true
  }

  it should "emit ToolCallFailed on tool error" in {
    val registry = mkFailRegistry()
    val state    = mkState(registry)
    val tc       = failToolCall()
    val events   = ArrayBuffer[AgentEvent]()

    ToolProcessor.processToolCallsWithEvents(state, Seq(tc), events += _, noOpContext)

    events.exists {
      case AgentEvent.ToolCallFailed(_, name, _, _) => name == "fail_tool"
      case _                                        => false
    } shouldBe true
  }

  it should "add tool result messages to state" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("msg-check")
    val events   = ArrayBuffer[AgentEvent]()

    val result       = ToolProcessor.processToolCallsWithEvents(state, Seq(tc), events += _, noOpContext)
    val toolMessages = result.conversation.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 1
  }

  // ── processToolCallsAsync (Sequential strategy == sync) ──────────────────────

  "ToolProcessor.processToolCallsAsync" should "produce same results as sync for Sequential strategy" in {
    val registry = mkEchoRegistry()
    val state    = mkState(registry)
    val tc       = echoToolCall("async-test")

    val asyncResult = ToolProcessor.processToolCallsAsync(
      state,
      Seq(tc),
      ToolExecutionStrategy.Sequential,
      noOpContext
    )

    val syncResult = ToolProcessor.processToolCalls(state, Seq(tc), noOpContext)

    val asyncMsgs = asyncResult.conversation.messages.collect { case m: ToolMessage => m }
    val syncMsgs  = syncResult.conversation.messages.collect { case m: ToolMessage => m }
    asyncMsgs should have size syncMsgs.size
    asyncMsgs.head.toolCallId shouldBe syncMsgs.head.toolCallId
  }
}
