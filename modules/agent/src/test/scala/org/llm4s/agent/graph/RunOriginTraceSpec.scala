package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.trace.TraceContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The trace context carriers on a child's origin and a run's config (design 4.58). */
class RunOriginTraceSpec extends AnyFlatSpec with Matchers {
  private val at      = Instant.parse("2026-10-21T10:00:00Z")
  private val context = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
  private val worker  = AgentId.unsafe("worker")

  "a RunOrigin" should "keep its trace context through its JSON form, and read one written before it as none" in {
    val origin = RunOrigin(ThreadId("p"), IdempotencyKey("k"), worker, at, 1, Some(context))
    upickle.default.read[RunOrigin](upickle.default.write(origin)) shouldBe origin
    upickle.default.write(origin) should include(context.traceParent)

    val before = """{"parent":"p","call":"k","agent":"worker","startedAt":"2026-10-21T10:00:00Z","depth":2}"""
    upickle.default.read[RunOrigin](before).trace shouldBe None
    RunOrigin(ThreadId("p"), IdempotencyKey("k"), worker, at, 2).trace shouldBe None
    origin.withTrace(None).withDepth(3).trace shouldBe None
    origin.withDepth(3).trace shouldBe Some(context)
  }

  "AgentTraceSpans" should "name spans injectively, whatever characters the ids hold" in {
    import org.llm4s.agent.AgentTraceSpans
    val token = Some(FencingToken(1L))
    AgentTraceSpans.run(ThreadId("a/b"), RunId("c"), token, None) should not be
      AgentTraceSpans.run(ThreadId("a"), RunId("b/c"), token, None)
    AgentTraceSpans.run(ThreadId("a"), RunId("b"), token, Some(context)) should not be
      AgentTraceSpans.run(ThreadId("a"), RunId("b"), Some(FencingToken(2L)), Some(context))
    AgentTraceSpans.event(context, "t/1", 2L) should not be AgentTraceSpans.event(context, "t", 12L)
    AgentTraceSpans.named("k", "a/b", "c") should not be AgentTraceSpans.named("k", "a", "b/c")
  }

  "RunConfig" should "carry the trace context a run continues" in {
    RunConfig().traceContext shouldBe None
    RunConfig().withTraceContext(context).traceContext shouldBe Some(context)
    RunConfig().withTraceContext(context).withTraceContext(None).traceContext shouldBe None
    RunConfig(traceContext = Some(context)).withRunId(RunId("r")).traceContext shouldBe Some(context)
  }
}
