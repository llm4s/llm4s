package org.llm4s.trace

import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.model.{ AssistantMessage, UsageSummary, UserMessage }
import org.llm4s.types.Result
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** Placed events are observations in one Langfuse trace; a child run nests in its parent's (design 4.58). */
class LangfuseSpanTreeSpec extends AnyFlatSpec with Matchers with OptionValues {

  private class Recording
      extends LangfuseTracing(
        langfuseUrl = "https://langfuse.example.com",
        publicKey = "pk-lf-test",
        secretKey = "sk-lf-test",
        environment = "test",
        release = "v0",
        version = "1",
        httpClient = new MockHttpClient(HttpResponse(200, """{"successes":1}""")),
        restoreInterrupt = () => ()
      ) {
    val sent: mutable.Buffer[ujson.Obj] = mutable.Buffer.empty
    override protected def sendBatch(events: Seq[ujson.Obj]): Result[Unit] = {
      sent ++= events
      Right(())
    }
    def ofType(kind: String): Seq[ujson.Value] = sent.toSeq.filter(_("type").str == kind).map(_("body"))
  }

  private val submitter = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
  private val parentRun = submitter.spanFor("run:parent")
  private val toolCall  = parentRun.spanFor("tool:c1")
  private val childRun  = toolCall.spanFor("run:child")
  private val t0        = Instant.parse("2026-10-21T10:00:00Z")

  private def agentEvent(name: String, payload: ujson.Obj, span: TraceSpan, at: Instant) =
    TraceEvent.CustomEvent(name, ujson.Obj("threadId" -> "t1", "payload" -> payload), at, Some(span))

  private def lone[A](as: Seq[A]): A = {
    as should have size 1
    as.head
  }

  "LangfuseTracing" should "nest a child run under its parent's trace and tool call, creating no trace of its own" in {
    val tracing = new Recording
    tracing.traceEvent(
      agentEvent(
        "agent.model_call_completed",
        ujson.Obj(
          "agent" -> "lead",
          "model" -> "m",
          "usage" -> ujson.Obj("promptTokens" -> 3, "completionTokens" -> 2, "totalTokens" -> 5)
        ),
        TraceSpan(parentRun.spanFor("event:1"), Some(parentRun), t0),
        t0
      )
    )
    tracing.traceEvent(
      TraceEvent.AgentRunEnded(
        "t1/~task/general/x",
        "child-run",
        "general",
        "completed",
        Seq(UserMessage("q"), AssistantMessage("a")),
        UsageSummary(),
        t0.plusSeconds(3),
        parentThreadId = Some("t1"),
        parentRunId = Some("parent-run"),
        span = Some(TraceSpan(childRun, Some(toolCall), t0.plusSeconds(1)))
      )
    )
    tracing.traceEvent(
      agentEvent(
        "agent.tool_executed",
        ujson.Obj("agent" -> "lead", "toolCallId" -> "c1", "tool" -> "task", "outcome" -> "Succeeded"),
        TraceSpan(toolCall, Some(parentRun), t0.plusSeconds(1)),
        t0.plusSeconds(4)
      )
    )
    tracing.traceEvent(
      TraceEvent.AgentRunEnded(
        "t1",
        "parent-run",
        "lead",
        "completed",
        Seq(UserMessage("q")),
        UsageSummary(),
        t0.plusSeconds(5),
        span = Some(TraceSpan(parentRun, Some(submitter), t0))
      )
    )

    // one trace: the parent's, created by the top-level run only, with the thread as its session
    val trace = lone(tracing.ofType("trace-create"))
    trace("id").str shouldBe submitter.traceId
    trace("sessionId").str shouldBe "t1"
    trace("metadata")("run_id").str shouldBe "parent-run"

    val observations = tracing.ofType("span-create") ++ tracing.ofType("generation-create")
    observations.foreach(_("traceId").str shouldBe submitter.traceId)
    def named(name: String) = observations.find(_("name").str == name).value

    val lead = named("invoke_agent lead")
    lead("id").str shouldBe parentRun.spanId
    lead.obj.contains("parentObservationId") shouldBe false
    lead("startTime").str shouldBe t0.toString
    lead("endTime").str shouldBe t0.plusSeconds(5).toString

    val tool = named("execute_tool task")
    tool("id").str shouldBe toolCall.spanId
    tool("parentObservationId").str shouldBe parentRun.spanId

    val child = named("invoke_agent general")
    child("id").str shouldBe childRun.spanId
    child("parentObservationId").str shouldBe toolCall.spanId
    child("metadata")("parent_run_id").str shouldBe "parent-run"

    val generation = lone(tracing.ofType("generation-create"))
    generation("name").str shouldBe "chat m"
    generation("parentObservationId").str shouldBe parentRun.spanId
    generation("usage")("input").num shouldBe 3

    // each message of a run is under the run
    val messages = observations.filter(_("name").str.matches("(User Input|LLM Generation) \\d+"))
    messages.map(_("parentObservationId").str).toSet shouldBe Set(parentRun.spanId, childRun.spanId)
  }

  it should "place any other placed event in its trace, under its parent" in {
    val tracing = new Recording
    val placed  = TraceSpan(parentRun.spanFor("e"), Some(parentRun), t0)
    tracing.traceEvent(TraceEvent.CustomEvent("graph.task_completed", ujson.Obj(), t0, Some(placed)))
    val body = lone(tracing.ofType("event-create"))
    body("id").str shouldBe placed.context.spanId
    body("traceId").str shouldBe submitter.traceId
    body("parentObservationId").str shouldBe parentRun.spanId
    // at its recorded time, not the export's
    body("timestamp").str shouldBe t0.toString
    body("startTime").str shouldBe t0.toString

    tracing.traceEvent(
      TraceEvent.TokenUsageRecorded(
        org.llm4s.llmconnect.model.TokenUsage(1, 1, 2),
        "m",
        "agent_completion",
        t0.plusSeconds(7),
        Some(TraceSpan(parentRun.spanFor("u"), Some(parentRun), t0.plusSeconds(7)))
      )
    )
    tracing.ofType("event-create").last("startTime").str shouldBe t0.plusSeconds(7).toString
  }

  it should "trace an unplaced run as before: its own trace, keyed by run" in {
    val tracing = new Recording
    tracing.traceEvent(TraceEvent.AgentRunEnded("t1", "run-1", "lead", "completed", Seq.empty, UsageSummary()))
    lone(tracing.ofType("trace-create"))("id").str shouldBe "run-1"
  }
}
