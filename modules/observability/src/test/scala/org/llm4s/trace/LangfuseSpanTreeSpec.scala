package org.llm4s.trace

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.model.{ AssistantMessage, UsageSummary, UserMessage }
import org.llm4s.types.Result
import org.scalatest.{ LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** Placed events are spans in one Langfuse trace; a child run nests in its parent's (design 4.58, #2883). */
class LangfuseSpanTreeSpec extends AnyFlatSpec with Matchers with OptionValues with LoneElement {

  private class Recording
      extends LangfuseTracing(
        langfuseUrl = "https://langfuse.example.com",
        publicKey = Secret("pk-lf-test"),
        secretKey = Secret("sk-lf-test"),
        environment = "test",
        release = "v0",
        version = "1",
        httpClient = new MockHttpClient(HttpResponse(200, "{}")),
        restoreInterrupt = () => ()
      ) {
    val sent: mutable.Buffer[SentSpan] = mutable.Buffer.empty
    override protected def sendBatch(request: ujson.Obj): Result[Unit] = {
      sent ++= SentSpan.all(request.render())
      Right(())
    }
    def ofKind(kind: String): Seq[SentSpan] = sent.toSeq.filter(_.kind == kind)
    def traces: Seq[SentSpan]               = sent.toSeq.filter(_.setsTrace)
  }

  private val submitter = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
  private val parentRun = submitter.spanFor("run:parent")
  private val toolCall  = parentRun.spanFor("tool:c1")
  private val childRun  = toolCall.spanFor("run:child")
  private val t0        = Instant.parse("2026-10-21T10:00:00Z")

  private def agentEvent(name: String, payload: ujson.Obj, span: TraceSpan, at: Instant) =
    TraceEvent.CustomEvent(name, ujson.Obj("threadId" -> "t1", "payload" -> payload), at, Some(span))

  "LangfuseTracing" should "nest a child run under its parent's trace and tool call, setting no trace of its own" in {
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
        TraceEvent.RunStatus.Completed,
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
        TraceEvent.RunStatus.Completed,
        Seq(UserMessage("q")),
        UsageSummary(),
        t0.plusSeconds(5),
        span = Some(TraceSpan(parentRun, Some(submitter), t0))
      )
    )

    // one trace: the parent's, set by the top-level run only, with the thread as its session
    val trace = tracing.traces.loneElement
    trace.traceId shouldBe submitter.traceId
    trace.str("langfuse.trace.name") shouldBe "LLM4S Agent Run"
    trace.str("session.id") shouldBe "t1"
    trace.traceMeta("run_id").str shouldBe "parent-run"
    trace.attr("langfuse.trace.tags") shouldBe ujson.Arr("llm4s", "agent", "conversation")

    tracing.sent.foreach(_.traceId shouldBe submitter.traceId)
    def named(name: String) = tracing.sent.find(_.name == name).value

    val lead = named("invoke_agent lead")
    lead.spanId shouldBe parentRun.spanId
    lead.parentSpanId shouldBe None
    lead.startTime shouldBe t0
    lead.endTime shouldBe t0.plusSeconds(5)
    lead.input.str shouldBe "q"
    lead.output.str shouldBe ""

    val tool = named("execute_tool task")
    tool.kind shouldBe "span"
    tool.spanId shouldBe toolCall.spanId
    tool.parentSpanId shouldBe Some(parentRun.spanId)
    tool.meta("threadId").str shouldBe "t1"
    tool.level shouldBe None

    val child = named("invoke_agent general")
    child.spanId shouldBe childRun.spanId
    child.parentSpanId shouldBe Some(toolCall.spanId)
    child.meta("parent_run_id").str shouldBe "parent-run"
    child.setsTrace shouldBe false

    val generation = tracing.ofKind("generation").loneElement
    generation.name shouldBe "chat m"
    generation.model shouldBe Some("m")
    generation.parentSpanId shouldBe Some(parentRun.spanId)
    generation.usage shouldBe ujson.Obj("input" -> 3, "output" -> 2, "total" -> 5)

    // each message of a run is under the run, with an id derived from the run's span and its index
    val messages = tracing.sent.filter(_.name.matches("(User Input|LLM Generation) \\d+"))
    messages.flatMap(_.parentSpanId).toSet shouldBe Set(parentRun.spanId, childRun.spanId)
    messages.map(_.spanId).distinct should have size messages.size
    messages.find(_.name == "LLM Generation 1").value.output("content").str shouldBe "a"
  }

  // Decided as intended (#2829): Langfuse merges trace attributes by trace id, and the backend keeps no state that
  // says whether an earlier run of the trace - on this machine or another - already set them.
  it should "set the trace again for a later run in it, under the same id, with that run's span unparented" in {
    val tracing = new Recording
    val first   = TraceContext.rootFor("run:first")
    val resumed = first.spanFor("run:resumed")
    tracing.traceEvent(
      TraceEvent.AgentRunEnded(
        "t1",
        "run-1",
        "lead",
        TraceEvent.RunStatus.Suspended,
        Seq(UserMessage("q")),
        UsageSummary(),
        t0.plusSeconds(1),
        span = Some(TraceSpan(first, None, t0))
      )
    )
    tracing.traceEvent(
      TraceEvent.AgentRunEnded(
        "t1",
        "run-2",
        "lead",
        TraceEvent.RunStatus.Completed,
        Seq(UserMessage("q"), AssistantMessage("a")),
        UsageSummary(),
        t0.plusSeconds(9),
        span = Some(TraceSpan(resumed, Some(first), t0.plusSeconds(8)))
      )
    )

    val traces = tracing.traces
    traces.map(_.traceId) shouldBe Seq(first.traceId, first.traceId)
    traces.map(_.traceMeta("status").str) shouldBe Seq("suspended", "completed")
    traces.map(_.output.str) shouldBe Seq("", "a")

    val runs = tracing.sent.filter(_.name == "invoke_agent lead")
    runs.map(_.spanId) shouldBe Seq(first.spanId, resumed.spanId)
    runs.foreach(_.parentSpanId shouldBe None)
  }

  it should "mark a failed run's span ERROR, with its status as the message" in {
    val tracing = new Recording
    tracing.traceEvent(
      TraceEvent.AgentRunEnded(
        "t1",
        "run-1",
        "lead",
        TraceEvent.RunStatus.Failed,
        Seq.empty,
        UsageSummary(),
        t0.plusSeconds(1),
        span = Some(TraceSpan(parentRun, None, t0))
      )
    )
    val run = tracing.sent.loneElement
    run.level shouldBe Some("ERROR")
    run.statusMessage shouldBe Some("failed")
    run.status.value("code").num shouldBe 2
  }

  it should "mark a placed tool call that errored ERROR" in {
    val tracing = new Recording
    tracing.traceEvent(
      agentEvent(
        "agent.tool_executed",
        ujson.Obj("tool" -> "calc", "outcome" -> "Errored"),
        TraceSpan(toolCall, Some(parentRun), t0),
        t0.plusSeconds(1)
      )
    )
    tracing.sent.loneElement.level shouldBe Some("ERROR")
  }

  it should "report a model call's output as billed: the completion plus the thinking it leaves out" in {
    def usage(usage: ujson.Obj): Option[ujson.Value] = {
      val tracing = new Recording
      tracing.traceEvent(
        agentEvent(
          "agent.model_call_completed",
          ujson.Obj("agent" -> "lead", "model" -> "o3", "usage" -> usage),
          TraceSpan(parentRun.spanFor("event:1"), Some(parentRun), t0),
          t0
        )
      )
      val generation = tracing.ofKind("generation").loneElement
      Option.when(generation.has("langfuse.observation.usage_details"))(generation.usage)
    }
    usage(
      ujson.Obj("promptTokens" -> 81, "completionTokens" -> 18, "totalTokens" -> 145, "thinkingTokens" -> 46)
    ).value("output").num shouldBe 64
    usage(
      ujson.Obj("promptTokens" -> 3, "completionTokens" -> 2, "totalTokens" -> 5, "thinkingTokens" -> ujson.Null)
    ).value("output").num shouldBe 2
    // an unknown count is left out, not sent as null
    usage(ujson.Obj("promptTokens" -> 3)).value shouldBe ujson.Obj("input" -> 3)
    usage(ujson.Obj()) shouldBe None
  }

  it should "place any other placed event in its trace, under its parent, at its recorded times" in {
    val tracing = new Recording
    val placed  = TraceSpan(parentRun.spanFor("e"), Some(parentRun), t0)
    tracing.traceEvent(TraceEvent.CustomEvent("graph.task_completed", ujson.Obj(), t0.plusSeconds(2), Some(placed)))
    val event = tracing.ofKind("event").loneElement
    event.spanId shouldBe placed.context.spanId
    event.traceId shouldBe submitter.traceId
    event.parentSpanId shouldBe Some(parentRun.spanId)
    event.startTime shouldBe t0
    event.endTime shouldBe t0.plusSeconds(2)

    tracing.traceEvent(
      TraceEvent.TokenUsageRecorded(
        org.llm4s.llmconnect.model.TokenUsage(1, 1, 2),
        ModelName("m"),
        "agent_completion",
        t0.plusSeconds(7),
        Some(TraceSpan(parentRun.spanFor("u"), Some(parentRun), t0.plusSeconds(7)))
      )
    )
    tracing.ofKind("event").last.startTime shouldBe t0.plusSeconds(7)
  }

  it should "trace an unplaced run as its own trace, whose ids derive from the run id" in {
    def send() = {
      val tracing = new Recording
      tracing.traceEvent(
        TraceEvent.AgentRunEnded(
          "t1",
          "run-1",
          "lead",
          TraceEvent.RunStatus.Completed,
          Seq(UserMessage("q"), AssistantMessage("a")),
          UsageSummary()
        )
      )
      tracing.sent.toSeq
    }
    val spans = send()
    val root  = TraceContext.rootFor("run-1")
    val trace = spans.filter(_.setsTrace).loneElement
    trace.traceId shouldBe root.traceId
    trace.spanId shouldBe root.spanId
    trace.name shouldBe "LLM4S Agent Run"
    trace.parentSpanId shouldBe None
    spans.tail.foreach(_.parentSpanId shouldBe Some(root.spanId))
    // the same run always names the same spans, so a resend updates rather than duplicates
    send().map(_.spanId) shouldBe spans.map(_.spanId)
  }
}
