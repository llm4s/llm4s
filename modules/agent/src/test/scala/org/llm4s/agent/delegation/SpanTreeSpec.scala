package org.llm4s.agent.delegation

import org.llm4s.agent.{ AgentTraceSpans, CompletionFixture }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.trace.{ TraceContext, TraceEvent }
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * One trace per run, across child runs (design 4.58): every event an agent's tracing sends is placed in a span tree -
 * the run's span over its events, a tool call's span over its duration, a delegated child's run under the tool call
 * that started it - and a run continues a given context as its remote parent; a recovered child, the trace its origin
 * recorded.
 */
class SpanTreeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {
  import DelegationSpec.*
  import NestedStreamSpec.Recording

  private val submitter = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")

  private def delegating = RoutingClient {
    case Lead(messages) if toolResults(messages).isEmpty =>
      Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "summarise X"))))
    case Lead(_)             => Right(CompletionFixture.simple("composed"))
    case Child("general", _) => Right(CompletionFixture.simple("the summary"))
  }

  private def runEnds(tracing: Recording) = tracing.events.collect { case e: TraceEvent.AgentRunEnded => e }

  private def placedOf(tracing: Recording, runId: String) =
    tracing.events.filter {
      case e: TraceEvent.CustomEvent => e.data.obj.get("runId").flatMap(_.strOpt).contains(runId)
      case _                         => false
    }

  "an agent run's tracing" should "place every event in one tree, a child's run under the task call that started it" in {
    val tracing = new Recording
    val agent   = lead(delegating).withTracing(tracing).withSubagents(general).build().value
    val run     = agent.start(ThreadId("t1"), "go").value
    run.await().value

    val parent  = runEnds(tracing).find(_.threadId == "t1").value
    val child   = runEnds(tracing).find(_.threadId != "t1").value
    val runSpan = parent.span.value
    // the run ends at its terminal event's time
    tracing.events.collect {
      case e: TraceEvent.CustomEvent if e.name == "graph.run_completed" && e.data("runId").str == run.runId.value =>
        e.timestamp
    } shouldBe Vector(parent.timestamp)
    // named by the run's incarnation, its claim's token included
    runSpan.context should not be AgentTraceSpans.run(ThreadId("t1"), run.runId, None, None)
    runSpan.parent shouldBe None
    !runSpan.startedAt.isAfter(parent.timestamp) shouldBe true

    // every event either run traced is in the parent's trace
    tracing.events.foreach(e => e.span.value.context.traceId shouldBe runSpan.context.traceId)

    // the parent's own events sit under its span
    val own = placedOf(tracing, run.runId.value)
    own should not be empty
    own.foreach(_.span.value.parent shouldBe Some(runSpan.context))
    own.map(_.span.value.context.spanId).distinct should have size own.size.toLong

    val task   = own.collect { case e: TraceEvent.CustomEvent if e.name == "agent.tool_executed" => e }.loneElement
    val origin = agent.children(ThreadId("t1")).value.loneElement.origin
    task.span.value.context shouldBe AgentTraceSpans.toolCall(runSpan.context, origin.call)
    task.data("payload")("idempotencyKey").str shouldBe origin.call.value
    !task.span.value.startedAt.isAfter(task.timestamp) shouldBe true

    // the child's run is under the task call's span, and its events under the child's run
    child.span.value.parent shouldBe Some(task.span.value.context)
    !child.span.value.startedAt.isBefore(task.span.value.startedAt) shouldBe true
    placedOf(tracing, child.runId).foreach(_.span.value.parent shouldBe Some(child.span.value.context))

    // the child's origin records the call's span
    agent.children(ThreadId("t1")).value.loneElement.origin.trace shouldBe Some(task.span.value.context)

    // usage records are under the run that made the call
    tracing.events.collect { case u: TraceEvent.TokenUsageRecorded => u }.foreach { u =>
      Set(Some(runSpan.context), Some(child.span.value.context)) should contain(u.span.value.parent)
    }
  }

  it should "continue a given trace context as the run's remote parent, its children included" in {
    val tracing = new Recording
    val agent   = lead(delegating).withTracing(tracing).withSubagents(general).build().value
    agent.run(ThreadId("t1"), "go", RunConfig().withTraceContext(submitter), Nil).value

    val parent = runEnds(tracing).find(_.threadId == "t1").value
    parent.span.value.parent shouldBe Some(submitter)
    parent.span.value.context.traceId shouldBe submitter.traceId
    tracing.events.foreach(_.span.value.context.traceId shouldBe submitter.traceId)
  }

  it should "give a call id the provider reuses in a later request a span of its own" in {
    val tracing = new Recording
    val calls   = new AtomicInteger(0)
    val probe   = AgentTool(SpecText.spec("probe"))((_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str("ok"))))
    val client = RoutingClient { case Lead(_) =>
      calls.incrementAndGet() match
        case 1 => Right(calling(call("same", "probe", ujson.Obj("text" -> "a"))))
        case 2 => Right(calling(call("same", "probe", ujson.Obj("text" -> "b"))))
        case _ => Right(CompletionFixture.simple("done"))
    }
    val agent = lead(client).withTracing(tracing).withTools(ToolSet.of(probe).value).build().value
    agent.run(ThreadId("t1"), "go").value
    val spans = tracing.events.collect {
      case e: TraceEvent.CustomEvent if e.name == "agent.tool_executed" => e.span.value.context
    }
    spans should have size 2
    spans.distinct should have size 2
  }

  it should "place a failed run's error at its terminal event, inside the run's span" in {
    val tracing = new Recording
    val failing = RoutingClient { case Lead(_) => Left(ProcessingError("provider", "the provider is down")) }
    val agent   = lead(failing).withTracing(tracing).build().value
    agent.run(ThreadId("t1"), "go").isLeft shouldBe true

    val ended = runEnds(tracing).loneElement
    ended.status shouldBe "failed"
    val error = tracing.events.collect { case e: TraceEvent.ErrorOccurred => e }.loneElement
    error.timestamp shouldBe ended.timestamp
    error.span.value.startedAt shouldBe ended.timestamp
    error.span.value.parent shouldBe Some(ended.span.value.context)
  }

  it should "give two runs that reuse one RunId spans of their own" in {
    val tracing = new Recording
    val agent   = lead(delegating).withTracing(tracing).withSubagents(general).build().value
    val reused  = RunId("reused")
    agent.run(ThreadId("t1"), "go", RunConfig().withRunId(reused).withTraceContext(submitter), Nil).value
    agent.run(ThreadId("t1"), "again", RunConfig().withRunId(reused).withTraceContext(submitter), Nil).value

    val runs = runEnds(tracing).filter(_.threadId == "t1")
    runs should have size 2
    runs.map(_.span.value.context.spanId).distinct should have size 2
    // and every span any run placed is placed once
    tracing.events.map(_.span.value.context.spanId).distinct should have size tracing.events.size.toLong
  }

  "a tool call suspended in one run and resumed in another" should "be one span per phase, each under the run that placed it" in {
    import DelegatedSuspensionSpec.{ approvingClient, asking, probe, suspendedOf }
    val tracing = new Recording
    val agent =
      lead(approvingClient()).withTracing(tracing).withSubagents(asking(probe(new AtomicInteger(0)))).build().value
    val config = () => RunConfig().withTraceContext(submitter)
    val first  = agent.run(ThreadId("t1"), "go", config(), Nil).value
    val id     = suspendedOf(first).delegated.loneElement._1
    agent.resume(ThreadId("t1"), Map(first.approve(id)), config()).value

    val runSpans = runEnds(tracing).map(e => e.runId -> e.span.value.context).toMap
    val tools    = tracing.events.collect { case e: TraceEvent.CustomEvent if e.name == "agent.tool_executed" => e }
    // the parent's task call (Asked, then Succeeded) and the child's probe call (NeedsApproval, then Succeeded)
    tools.map(_.data("payload")("outcome").str).sorted shouldBe Vector(
      "Asked",
      "NeedsApproval",
      "Succeeded",
      "Succeeded"
    )
    tools.map(_.span.value.context.spanId).distinct should have size 4
    tools.foreach { e =>
      e.span.value.context.traceId shouldBe submitter.traceId
      e.span.value.parent shouldBe Some(runSpans(e.data("runId").str))
    }
    // no span id is placed by two events, whatever their kind
    tracing.events.map(_.span.value.context.spanId).distinct should have size tracing.events.size.toLong

    // the task call's one logical span is placed by the phase that ended it; every child run is under it
    val origin = agent.children(ThreadId("t1")).value.loneElement.origin
    val call   = AgentTraceSpans.toolCall(submitter, origin.call)
    origin.trace shouldBe Some(call)
    tools.filter(_.span.value.context == call).loneElement.data("payload")("outcome").str shouldBe "Succeeded"
    val children = runEnds(tracing).filter(_.threadId != "t1")
    children should have size 2
    children.foreach(_.span.value.parent shouldBe Some(call))
  }

  "a child recovered by a later run of its parent" should "continue the trace its origin recorded, under the same call" in {
    val tracing    = new Recording
    val childCalls = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) =>
        childCalls.incrementAndGet() match
          case 1 => Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
          case 2 => Left(ProcessingError("provider", "the provider is down"))
          case _ => Right(CompletionFixture.simple("""{"summary":"recovered","sources":1}"""))
    }
    val probe = AgentTool(SpecText.spec("probe"))((_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str("probed"))))
    val agent = lead(client)
      .withTracing(tracing)
      .withSubagents(researcher.withTools(ToolSet.of(probe).value).withFailure(ChildFailure.Fail))
      .build()
      .value
    val config = () => RunConfig().withTraceContext(submitter)
    agent.run(ThreadId("t1"), "go", config(), Nil).isLeft shouldBe true
    agent.recover(ThreadId("t1"), config()).value

    val children  = runEnds(tracing).filter(_.threadId != "t1")
    val recovered = children.find(_.status == "completed").value
    val callSpan =
      AgentTraceSpans.toolCall(submitter, agent.children(ThreadId("t1")).value.loneElement.origin.call)
    recovered.span.value.parent shouldBe Some(callSpan)
    children.foreach(_.span.value.parent shouldBe Some(callSpan))
    // the recovered parent run places its task call at the same span, in the same trace
    val parents = runEnds(tracing).filter(_.threadId == "t1")
    parents.foreach(_.span.value.parent shouldBe Some(submitter))
    tracing.events
      .collect {
        case e: TraceEvent.CustomEvent
            if e.name == "agent.tool_executed" && e.data("payload")("tool").str == "task_researcher" =>
          e
      }
      .foreach(_.span.value.context shouldBe callSpan)
  }
}
