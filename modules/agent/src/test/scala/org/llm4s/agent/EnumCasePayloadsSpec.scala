package org.llm4s.agent

import org.llm4s.agent.delegation.{ ChildInterrupt, ChildRef, DelegatedRequest }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.MiddlewareId
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome, ToolSuccess }
import org.llm4s.agent.graph.toolloop._
import org.llm4s.llmconnect.model.{ ToolCall, UsageSummary }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The classes that the `@Stable` enum cases which may gain a field hold (#2781): `AgentStatus.Suspended`'s
 * [[Suspension]], the `RunEvent` cases' `...Details`, `StreamEvent.Live`'s [[LiveEvent]] and `ToolOutcome.Success`'s
 * [[ToolSuccess]]. Each has a private constructor and `copy`, so every setter is checked here: one that wrote the
 * wrong field would compile.
 */
class EnumCasePayloadsSpec extends AnyFlatSpec with Matchers {

  private val usage      = UsageSummary().withRequestCount(3L)
  private val agent      = AgentId.unsafe("worker")
  private val middleware = MiddlewareId("review")

  "Suspension" should "default every list to empty and set each through its own setter" in {
    val base = Suspension()
    base.approvals shouldBe empty
    base.questions shouldBe empty
    base.middlewareQuestions shouldBe empty
    base.breakpoints shouldBe empty
    base.delegated shouldBe empty

    val call     = ToolCall("call-1", "deploy", ujson.Obj())
    val key      = IdempotencyKey("call-1")
    val approval = InterruptId("a") -> ApprovalRequest("m1", call, "risky", ApprovalSource.Tool, key)
    val question = InterruptId("q") -> ToolQuestionRequest("m1", call, ujson.Str("which?"), key)
    val asked =
      InterruptId("m") -> MiddlewareQuestionRequest(agent, middleware, MiddlewareHook.BeforeAgent, ujson.Str("go?"))
    val breakpoint = InterruptId("b") -> BreakpointRequest(NodeId("tools"), BreakpointPhase.Before)
    val child      = ChildRef(ThreadId("child"), RunId("r"), agent, None, 1)
    val waiting    = InterruptId("d") -> DelegatedRequest(child, ChildInterrupt.Approval(approval._1, approval._2))
    base.withApprovals(Vector(approval)).approvals shouldBe Vector(approval)
    base.withQuestions(Vector(question)).questions shouldBe Vector(question)
    base.withMiddlewareQuestions(Vector(asked)).middlewareQuestions shouldBe Vector(asked)
    base.withBreakpoints(Vector(breakpoint)).breakpoints shouldBe Vector(breakpoint)
    base.withDelegated(Vector(waiting)).delegated shouldBe Vector(waiting)
    base.withDelegated(Vector(waiting)).approvals shouldBe empty
    base.withApprovals(Vector(approval)).delegated shouldBe empty
    Suspension(Vector(approval), Vector(question), Vector(asked), Vector(breakpoint), Vector(waiting)) shouldBe
      base
        .withApprovals(Vector(approval))
        .withQuestions(Vector(question))
        .withMiddlewareQuestions(Vector(asked))
        .withBreakpoints(Vector(breakpoint))
        .withDelegated(Vector(waiting))
  }

  it should "be what AgentStatus.Suspended holds" in {
    val breakpoint = InterruptId("b") -> BreakpointRequest(NodeId("tools"), BreakpointPhase.Before)
    val suspension = Suspension(breakpoints = Vector(breakpoint))
    (AgentStatus.Suspended(suspension): AgentStatus) match
      case AgentStatus.Suspended(held) => held.breakpoints shouldBe Vector(breakpoint)
      case other                       => fail(s"expected Suspended, got $other")
  }

  "RunStartedDetails" should "set each field through its own setter" in {
    val base = RunStartedDetails()
    base shouldBe RunStartedDetails(None, None)
    base.withTenantId("t").tenantId shouldBe Some("t")
    base.withTenantId(Some("t")).withTenantId(None).tenantId shouldBe None
    base.withPrincipal("p").principal shouldBe Some("p")
    base.withPrincipal(Some("p")).withPrincipal(None).principal shouldBe None
    base.withTenantId("t").principal shouldBe None
    base.withPrincipal("p").tenantId shouldBe None
  }

  "RunRecoveredDetails" should "set each field through its own setter" in {
    val base = RunRecoveredDetails("c1")
    base shouldBe RunRecoveredDetails("c1", None, None, None)
    base.withFromCheckpoint("c2").fromCheckpoint shouldBe "c2"
    base.withTenantId("t").tenantId shouldBe Some("t")
    base.withTenantId(Some("t")).withTenantId(None).tenantId shouldBe None
    base.withPrincipal("p").principal shouldBe Some("p")
    base.withPrincipal(Some("p")).withPrincipal(None).principal shouldBe None
    base.withTakenOverFrom("r0").takenOverFrom shouldBe Some("r0")
    base.withTakenOverFrom(Some("r0")).withTakenOverFrom(None).takenOverFrom shouldBe None
    base.withTakenOverFrom("r0").tenantId shouldBe None
    base.withTenantId("t").fromCheckpoint shouldBe "c1"
  }

  "RunResumedDetails" should "set each field through its own setter" in {
    val base = RunResumedDetails(Vector("i"))
    base shouldBe RunResumedDetails(Vector("i"), None, None)
    base.withAnswered(Vector("j", "k")).answered shouldBe Vector("j", "k")
    base.withTenantId("t").tenantId shouldBe Some("t")
    base.withTenantId(Some("t")).withTenantId(None).tenantId shouldBe None
    base.withPrincipal("p").principal shouldBe Some("p")
    base.withPrincipal(Some("p")).withPrincipal(None).principal shouldBe None
    base.withPrincipal("p").answered shouldBe Vector("i")
  }

  "RunFailedDetails" should "set each field through its own setter" in {
    val base = RunFailedDetails("boom")
    base shouldBe RunFailedDetails("boom", None, 0L)
    base.withMessage("other").message shouldBe "other"
    base.withUsage(usage).usage shouldBe Some(usage)
    base.withUsage(Some(usage)).withUsage(None).usage shouldBe None
    base.withSteps(7L).steps shouldBe 7L
    base.withSteps(7L).message shouldBe "boom"
    base.withMessage("other").steps shouldBe 0L
  }

  "RunStoppedDetails" should "set each field through its own setter" in {
    val base = RunStoppedDetails()
    base shouldBe RunStoppedDetails.empty
    base shouldBe RunStoppedDetails(None, 0L)
    base.withUsage(usage).usage shouldBe Some(usage)
    base.withUsage(Some(usage)).withUsage(None).usage shouldBe None
    base.withSteps(7L).steps shouldBe 7L
    base.withSteps(7L).usage shouldBe None
  }

  "StateUpdatedDetails" should "set each field through its own setter" in {
    val base = StateUpdatedDetails("c1")
    base shouldBe StateUpdatedDetails("c1", None, None, None)
    base.withFromCheckpoint("c2").fromCheckpoint shouldBe "c2"
    base.withAsNode("n").asNode shouldBe Some("n")
    base.withAsNode(Some("n")).withAsNode(None).asNode shouldBe None
    base.withTenantId("t").tenantId shouldBe Some("t")
    base.withTenantId(Some("t")).withTenantId(None).tenantId shouldBe None
    base.withPrincipal("p").principal shouldBe Some("p")
    base.withPrincipal(Some("p")).withPrincipal(None).principal shouldBe None
    base.withAsNode("n").tenantId shouldBe None
    base.withTenantId("t").asNode shouldBe None
  }

  "ThreadForkedDetails" should "set each field through its own setter" in {
    val base = ThreadForkedDetails("src", "c1")
    base shouldBe ThreadForkedDetails("src", "c1", None, None)
    base.withFromThread("other").fromThread shouldBe "other"
    base.withFromCheckpoint("c2").fromCheckpoint shouldBe "c2"
    base.withTenantId("t").tenantId shouldBe Some("t")
    base.withTenantId(Some("t")).withTenantId(None).tenantId shouldBe None
    base.withPrincipal("p").principal shouldBe Some("p")
    base.withPrincipal(Some("p")).withPrincipal(None).principal shouldBe None
    base.withFromThread("other").fromCheckpoint shouldBe "c1"
    base.withFromCheckpoint("c2").fromThread shouldBe "src"
  }

  "LiveEvent" should "set each field through its own setter" in {
    val base = LiveEvent("th", "r", FencingToken(1), "task", "node", "name", 1, ujson.Num(1))
    base.withThreadId("b").threadId shouldBe "b"
    base.withRunId("b").runId shouldBe "b"
    base.withToken(FencingToken(7)).token shouldBe FencingToken(7)
    base.withTaskId("b").taskId shouldBe "b"
    base.withNodeId("b").nodeId shouldBe "b"
    base.withName("b").name shouldBe "b"
    base.withVersion(7).version shouldBe 7
    base.withPayload(ujson.Str("b")).payload shouldBe ujson.Str("b")
    val changed = base.withThreadId("b").withRunId("c").withTaskId("d").withNodeId("e").withName("f")
    (changed.threadId, changed.runId, changed.taskId, changed.nodeId, changed.name) shouldBe (("b", "c", "d", "e", "f"))
    changed.version shouldBe 1
  }

  "ToolSuccess" should "set its content and add to its update" in {
    val key    = StateKey.replace[Int]("enum-case-payloads-count", 0)
    val update = StateUpdate.update(key, 7)
    val base   = ToolSuccess(ToolContent.Json(ujson.Num(1)))
    base.update shouldBe StateUpdate.empty
    base.withContent(ToolContent.Json(ujson.Num(2))).content shouldBe ToolContent.Json(ujson.Num(2))
    base.addUpdate(update).update.isEmpty shouldBe false
    base.addUpdate(update).content shouldBe base.content
    base.withContent(ToolContent.Json(ujson.Num(2))).update shouldBe StateUpdate.empty
  }

  "ToolOutcome.success" should "build a Success holding the content and the update" in {
    val key    = StateKey.replace[Int]("enum-case-payloads-other", 0)
    val update = StateUpdate.update(key, 7)
    ToolOutcome.success(ToolContent.Json(ujson.Num(1))) shouldBe
      ToolOutcome.Success(ToolSuccess(ToolContent.Json(ujson.Num(1)), StateUpdate.empty))
    ToolOutcome.success(ToolContent.Json(ujson.Num(1)), update) shouldBe
      ToolOutcome.Success(ToolSuccess(ToolContent.Json(ujson.Num(1)), update))
  }
}
