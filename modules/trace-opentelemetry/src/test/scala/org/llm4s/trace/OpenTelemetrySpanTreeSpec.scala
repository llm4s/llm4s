package org.llm4s.trace

import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.`export`.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import org.llm4s.llmconnect.model.{ AssistantMessage, TokenUsage, UsageSummary, UserMessage }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit }
import scala.jdk.CollectionConverters.*

/** Placed trace events become one OpenTelemetry span tree, with exactly their ids (design 4.58). */
class OpenTelemetrySpanTreeSpec extends AnyWordSpec with Matchers:

  final private class Collecting extends SpanExporter:
    val spans = new ConcurrentLinkedQueue[SpanData]()
    def `export`(batch: java.util.Collection[SpanData]): CompletableResultCode =
      spans.addAll(batch)
      CompletableResultCode.ofSuccess()
    def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

  private def withTracing(body: (OpenTelemetryTracing, () => Vector[SpanData]) => Unit): Unit =
    val exporter = new Collecting
    val tracing  = OpenTelemetryTracing.exportingTo("span-tree-spec", exporter)
    try body(tracing, () => exporter.spans.asScala.toVector)
    finally tracing.shutdown()

  private val submitter = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
  private val t0        = Instant.parse("2026-10-21T10:00:00Z")

  private def agentEvent(name: String, payload: ujson.Obj, span: TraceSpan, at: Instant): TraceEvent.CustomEvent =
    TraceEvent.CustomEvent(
      name,
      ujson.Obj("threadId" -> "thread-1", "runId" -> "run-1", "name" -> name, "version" -> 1, "payload" -> payload),
      at,
      Some(span)
    )

  "OpenTelemetryTracing" should {

    "export a placed event with exactly its ids, its remote parent and its start and end" in withTracing {
      (tracing, spans) =>
        val own = submitter.spanFor("x")
        tracing.traceEvent(
          TraceEvent.CustomEvent(
            "graph.task_completed",
            ujson.Obj(),
            t0.plusSeconds(2),
            Some(TraceSpan(own, Some(submitter), t0))
          )
        )

        val span = spans().loneElement
        span.getTraceId shouldBe submitter.traceId
        span.getSpanId shouldBe own.spanId
        span.getParentSpanId shouldBe submitter.spanId
        span.getParentSpanContext.isRemote shouldBe true
        span.getStartEpochNanos shouldBe TimeUnit.SECONDS.toNanos(t0.getEpochSecond)
        span.getEndEpochNanos shouldBe TimeUnit.SECONDS.toNanos(t0.getEpochSecond + 2)
        span.getKind shouldBe SpanKind.INTERNAL
    }

    "export a placed event without a parent as the root of its own trace" in withTracing { (tracing, spans) =>
      val root = TraceContext.rootFor("run-9")
      tracing.traceEvent(TraceEvent.CustomEvent("graph.run_started", ujson.Obj(), t0, Some(TraceSpan(root, None, t0))))
      val span = spans().loneElement
      span.getTraceId shouldBe root.traceId
      span.getSpanId shouldBe root.spanId
      span.getParentSpanContext.isValid shouldBe false
    }

    "give an event without a placement random ids, as before" in withTracing { (tracing, spans) =>
      tracing.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
      tracing.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
      val exported = spans()
      exported.map(_.getTraceId).distinct should have size 2
      exported.foreach(_.getParentSpanContext.isValid shouldBe false)
    }

    "build one tree for a run, its model and tool calls, and a child run under the tool call that started it" in withTracing {
      (tracing, spans) =>
        val parentRun = submitter.spanFor("run:parent")
        val toolCall  = parentRun.spanFor("tool:call-1")
        val modelCall = parentRun.spanFor("event:3")
        val childRun  = toolCall.spanFor("run:child")
        val secret    = "the user's secret question"

        tracing.traceEvent(
          agentEvent(
            "agent.model_call_completed",
            ujson.Obj(
              "agent"         -> "lead",
              "model"         -> "gpt-x",
              "attempts"      -> 1,
              "toolCalls"     -> 1,
              "usage"         -> ujson.Obj("promptTokens" -> 12, "completionTokens" -> 3, "totalTokens" -> 15),
              "estimatedCost" -> 0.25
            ),
            TraceSpan(modelCall, Some(parentRun), t0.plusSeconds(1)),
            t0.plusSeconds(1)
          )
        )
        // the child ends first, under the tool call's span, which is exported only when the call ends
        tracing.traceEvent(
          TraceEvent.AgentRunEnded(
            "thread-1/~task/researcher/abc",
            "child-run",
            "researcher",
            "completed",
            Seq(UserMessage(secret), AssistantMessage("a secret answer")),
            UsageSummary().add("gpt-x", TokenUsage(5, 2, 7), None),
            t0.plusSeconds(4),
            parentThreadId = Some("thread-1"),
            parentRunId = Some("parent-run"),
            span = Some(TraceSpan(childRun, Some(toolCall), t0.plusSeconds(2)))
          )
        )
        tracing.traceEvent(
          agentEvent(
            "agent.tool_executed",
            ujson.Obj(
              "agent"      -> "lead",
              "toolCallId" -> "call-1",
              "tool"       -> "task",
              "duration"   -> 3000,
              "outcome"    -> "Errored"
            ),
            TraceSpan(toolCall, Some(parentRun), t0.plusSeconds(2)),
            t0.plusSeconds(5)
          )
        )
        tracing.traceEvent(
          TraceEvent.AgentRunEnded(
            "thread-1",
            "parent-run",
            "lead",
            "failed",
            Seq(UserMessage(secret)),
            UsageSummary(),
            t0.plusSeconds(6),
            span = Some(TraceSpan(parentRun, Some(submitter), t0))
          )
        )

        val byName = spans().map(s => s.getName -> s).toMap
        byName.keySet shouldBe Set("chat gpt-x", "invoke_agent researcher", "execute_tool task", "invoke_agent lead")
        spans().map(_.getTraceId).distinct shouldBe Vector(submitter.traceId)

        val lead = byName("invoke_agent lead")
        lead.getSpanId shouldBe parentRun.spanId
        lead.getParentSpanId shouldBe submitter.spanId
        lead.getStatus.getStatusCode shouldBe StatusCode.ERROR

        val chat = byName("chat gpt-x")
        chat.getParentSpanId shouldBe parentRun.spanId
        chat.getKind shouldBe SpanKind.CLIENT
        val chatAttributes = chat.getAttributes.asMap.asScala.map((k, v) => k.getKey -> v)
        chatAttributes("gen_ai.operation.name") shouldBe "chat"
        chatAttributes("gen_ai.request.model") shouldBe "gpt-x"
        chatAttributes("gen_ai.usage.input_tokens") shouldBe java.lang.Long.valueOf(12L)
        chatAttributes("gen_ai.usage.output_tokens") shouldBe java.lang.Long.valueOf(3L)

        val tool = byName("execute_tool task")
        tool.getSpanId shouldBe toolCall.spanId
        tool.getParentSpanId shouldBe parentRun.spanId
        tool.getKind shouldBe SpanKind.INTERNAL
        tool.getStatus.getStatusCode shouldBe StatusCode.ERROR

        val child = byName("invoke_agent researcher")
        child.getSpanId shouldBe childRun.spanId
        child.getParentSpanId shouldBe toolCall.spanId
        child.getStatus.getStatusCode shouldBe StatusCode.UNSET

        // rule 10: no attribute of any span carries message content
        spans()
          .flatMap(_.getAttributes.asMap.asScala.values.map(_.toString))
          .filter(_.contains("secret")) shouldBe empty
    }
  }

  extension [A](as: Vector[A])
    private def loneElement: A =
      as should have size 1
      as.head
