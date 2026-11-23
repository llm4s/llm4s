package org.llm4s.agent

import org.llm4s.agent.delegation.ChildRef
import org.llm4s.agent.events._
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.toolloop._
import org.llm4s.agent.guardrails.builtin.{ InjectionCategory, InjectionPattern }
import org.llm4s.error.{ LLMError, ProcessingError }
import org.llm4s.llmconnect.model.{ FinishReason, RequestAdjustment, RequestOption, ServiceTier, ToolCall, UserMessage }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
 * The `@Stable` case classes of `llm4s-agent` that may gain a field (#2778): each has a private constructor and
 * `copy`, so the `with*` setters are the only way to change one. Every setter is checked here, because a setter that
 * wrote the wrong field would compile.
 */
class GrowthSafeTypesSpec extends AnyFlatSpec with Matchers {

  private val errorA: LLMError               = ProcessingError("a", "first")
  private val errorB: LLMError               = ProcessingError("b", "second")
  private val regexA: Regex                  = "a+".r
  private val regexB: Regex                  = "b+".r
  private val listenerA: StreamEvent => Unit = _ => ()
  private val listenerB: StreamEvent => Unit = _ => ()
  private val builder: AgentBuilder          = Agent.builder("target", new FailingLLMClient(errorA))
  private val key: StateKey[Int, Int]        = StateKey.replace[Int]("growth-safe-count", 0)
  private val updateB: StateUpdate           = StateUpdate.update(key, 7)
  private val originA: RunOrigin =
    RunOrigin(ThreadId("parent-a"), IdempotencyKey("call-a"), AgentId.unsafe("a"), Instant.EPOCH)
  private val originB: RunOrigin =
    RunOrigin(ThreadId("parent-b"), IdempotencyKey("call-b"), AgentId.unsafe("b"), Instant.EPOCH)

  "ContextWindowConfig" should "set each field through its own setter" in {
    val base = ContextWindowConfig(None, None, false, 1, PruningStrategy.OldestFirst)
    base.withMaxTokens(7).maxTokens shouldBe Some(7)
    base.withMaxTokens(Some(7)).withMaxTokens(None).maxTokens shouldBe None
    base.withMaxMessages(7).maxMessages shouldBe Some(7)
    base.withMaxMessages(Some(7)).withMaxMessages(None).maxMessages shouldBe None
    base.withPreserveSystemMessage(true).preserveSystemMessage shouldBe true
    base.withMinRecentTurns(7).minRecentTurns shouldBe 7
    base.withPruningStrategy(PruningStrategy.MiddleOut).pruningStrategy shouldBe PruningStrategy.MiddleOut
    base.withMaxTokens(7).pruningStrategy shouldBe base.pruningStrategy
    base.withPruningStrategy(PruningStrategy.MiddleOut).maxTokens shouldBe base.maxTokens
  }

  "PruningStrategy.AdaptiveWindowing" should "set each field through its own setter" in {
    val base = PruningStrategy.AdaptiveWindowing(1, None, None, 0.25)
    base.withContextWindowSize(7).contextWindowSize shouldBe 7
    base.withInputCostPerToken(0.75).inputCostPerToken shouldBe Some(0.75)
    base.withInputCostPerToken(Some(0.75)).withInputCostPerToken(None).inputCostPerToken shouldBe None
    base.withOutputCostPerToken(0.75).outputCostPerToken shouldBe Some(0.75)
    base.withOutputCostPerToken(Some(0.75)).withOutputCostPerToken(None).outputCostPerToken shouldBe None
    base.withCostSensitivity(0.75).costSensitivity shouldBe 0.75
    base.withContextWindowSize(7).costSensitivity shouldBe base.costSensitivity
    base.withCostSensitivity(0.75).contextWindowSize shouldBe base.contextWindowSize
  }

  "Handoff" should "set each field through its own setter" in {
    val base = Handoff("a", None, None, false)
    base.withId("b").id shouldBe "b"
    base.withTarget(builder).target shouldBe Some(builder)
    base.withTarget(Some(builder)).withTarget(None).target shouldBe None
    base.withTransferReason("b").transferReason shouldBe Some("b")
    base.withTransferReason(Some("b")).withTransferReason(None).transferReason shouldBe None
    base.withPreserveContext(true).preserveContext shouldBe true
    base.withId("b").preserveContext shouldBe base.preserveContext
    base.withPreserveContext(true).id shouldBe base.id
  }

  "ChildRef" should "set each field through its own setter" in {
    val base = ChildRef(ThreadId("a"), RunId("a"), AgentId.unsafe("a"), None, 1)
    base.withThreadId(ThreadId("b")).threadId shouldBe ThreadId("b")
    base.withRunId(RunId("b")).runId shouldBe RunId("b")
    base.withAgent(AgentId.unsafe("b")).agent shouldBe AgentId.unsafe("b")
    base.withToolCallId(ToolCallId("b")).toolCallId shouldBe Some(ToolCallId("b"))
    base.withToolCallId(Some(ToolCallId("b"))).withToolCallId(None).toolCallId shouldBe None
    base.withDepth(7).depth shouldBe 7
    base.withThreadId(ThreadId("b")).depth shouldBe base.depth
    base.withDepth(7).threadId shouldBe base.threadId
  }

  "CallUsage" should "set each field through its own setter" in {
    val base = CallUsage(1, 1, 1, None)
    base.withPromptTokens(7).promptTokens shouldBe 7
    base.withCompletionTokens(7).completionTokens shouldBe 7
    base.withTotalTokens(7).totalTokens shouldBe 7
    base.withThinkingTokens(7).thinkingTokens shouldBe Some(7)
    base.withThinkingTokens(Some(7)).withThinkingTokens(None).thinkingTokens shouldBe None
    base.withPromptTokens(7).thinkingTokens shouldBe base.thinkingTokens
    base.withThinkingTokens(7).promptTokens shouldBe base.promptTokens
  }

  "ModelCallCompleted" should "set each field through its own setter" in {
    val base = ModelCallCompleted("a", "a", 1, 1, None, None, None, Nil, None, None)
    base.withAgent("b").agent shouldBe "b"
    base.withModel("b").model shouldBe "b"
    base.withAttempts(7).attempts shouldBe 7
    base.withToolCalls(7).toolCalls shouldBe 7
    base.withUsage(CallUsage(1, 2, 3, None)).usage shouldBe Some(CallUsage(1, 2, 3, None))
    base.withUsage(Some(CallUsage(1, 2, 3, None))).withUsage(None).usage shouldBe None
    base.withEstimatedCost(0.75).estimatedCost shouldBe Some(0.75)
    base.withEstimatedCost(Some(0.75)).withEstimatedCost(None).estimatedCost shouldBe None
    base.withProvider(ProviderId("openai")).provider shouldBe Some(ProviderId("openai"))
    base.withProvider(Some(ProviderId("openai"))).withProvider(None).provider shouldBe None
    base
      .withAdjustments(List(RequestAdjustment.Dropped(RequestOption.Temperature, "unsupported")))
      .adjustments shouldBe List(RequestAdjustment.Dropped(RequestOption.Temperature, "unsupported"))
    base.withFinishReason(FinishReason.Stop).finishReason shouldBe Some(FinishReason.Stop)
    base.withFinishReason(Some(FinishReason.Stop)).withFinishReason(None).finishReason shouldBe None
    base.withServedTier(ServiceTier.Auto).servedTier shouldBe Some(ServiceTier.Auto)
    base.withServedTier(Some(ServiceTier.Auto)).withServedTier(None).servedTier shouldBe None
    base.withAgent("b").servedTier shouldBe base.servedTier
    base.withServedTier(ServiceTier.Auto).agent shouldBe base.agent
  }

  "ToolExecuted" should "set each field through its own setter" in {
    val base = ToolExecuted("a", "a", "a", 1.second, ToolExecutionOutcome.Succeeded, None)
    base.withAgent("b").agent shouldBe "b"
    base.withToolCallId("b").toolCallId shouldBe "b"
    base.withTool("b").tool shouldBe "b"
    base.withDuration(7.seconds).duration shouldBe 7.seconds
    base.withOutcome(ToolExecutionOutcome.Errored).outcome shouldBe ToolExecutionOutcome.Errored
    base.withIdempotencyKey("b").idempotencyKey shouldBe Some("b")
    base.withIdempotencyKey(Some("b")).withIdempotencyKey(None).idempotencyKey shouldBe None
    base.withAgent("b").idempotencyKey shouldBe base.idempotencyKey
    base.withIdempotencyKey("b").agent shouldBe base.agent
  }

  "HandedOff" should "set each field through its own setter" in {
    val base = HandedOff("a", "a")
    base.withFrom("b").from shouldBe "b"
    base.withTo("b").to shouldBe "b"
    base.withFrom("b").to shouldBe base.to
    base.withTo("b").from shouldBe base.from
  }

  "GuardrailBlock" should "set each field through its own setter" in {
    val base = GuardrailBlock("a", GuardrailPhase.Input)
    base.withGuardrail("b").guardrail shouldBe "b"
    base.withPhase(GuardrailPhase.Output).phase shouldBe GuardrailPhase.Output
    base.withGuardrail("b").phase shouldBe base.phase
    base.withPhase(GuardrailPhase.Output).guardrail shouldBe base.guardrail
  }

  "ModelCallStarted" should "set each field through its own setter" in {
    val base = ModelCallStarted("a", 1)
    base.withAgent("b").agent shouldBe "b"
    base.withAttempt(7).attempt shouldBe 7
    base.withAgent("b").attempt shouldBe base.attempt
    base.withAttempt(7).agent shouldBe base.agent
  }

  "TextDelta" should "set each field through its own setter" in {
    val base = TextDelta(1, "a")
    base.withAttempt(7).attempt shouldBe 7
    base.withText("b").text shouldBe "b"
    base.withAttempt(7).text shouldBe base.text
    base.withText("b").attempt shouldBe base.attempt
  }

  "ThinkingDelta" should "set each field through its own setter" in {
    val base = ThinkingDelta(1, "a")
    base.withAttempt(7).attempt shouldBe 7
    base.withText("b").text shouldBe "b"
    base.withAttempt(7).text shouldBe base.text
    base.withText("b").attempt shouldBe base.attempt
  }

  "ToolCallStarted" should "set each field through its own setter" in {
    val base = ToolCallStarted("a", "a", ujson.Null)
    base.withToolCallId("b").toolCallId shouldBe "b"
    base.withTool("b").tool shouldBe "b"
    base.withArguments(ujson.Str("b")).arguments shouldBe ujson.Str("b")
    base.withToolCallId("b").arguments shouldBe base.arguments
    base.withArguments(ujson.Str("b")).toolCallId shouldBe base.toolCallId
  }

  "ToolCallResult" should "set each field through its own setter" in {
    val base = ToolCallResult("a", "a", false)
    base.withToolCallId("b").toolCallId shouldBe "b"
    base.withContent("b").content shouldBe "b"
    base.withIsError(true).isError shouldBe true
    base.withToolCallId("b").isError shouldBe base.isError
    base.withIsError(true).toolCallId shouldBe base.toolCallId
  }

  "PendingWrite" should "set each field through its own setter" in {
    val base = PendingWrite("a", "a", "a", Vector.empty, Vector.empty, None, None)
    base.withCheckpointId("b").checkpointId shouldBe "b"
    base.withTaskId("b").taskId shouldBe "b"
    base.withNodeId("b").nodeId shouldBe "b"
    base.withOperations(Vector(EncodedOperation.Remove("k"))).operations shouldBe Vector(EncodedOperation.Remove("k"))
    base.withRoutes(Vector(EncodedRoute.Goto("node"))).routes shouldBe Vector(EncodedRoute.Goto("node"))
    base.withSuspension(EncodedSuspension("node", VersionedJson(1, ujson.Null))).suspension shouldBe Some(
      EncodedSuspension("node", VersionedJson(1, ujson.Null))
    )
    base
      .withSuspension(Some(EncodedSuspension("node", VersionedJson(1, ujson.Null))))
      .withSuspension(None)
      .suspension shouldBe None
    base.withResultDigest("b").resultDigest shouldBe Some("b")
    base.withResultDigest(Some("b")).withResultDigest(None).resultDigest shouldBe None
    base.withCheckpointId("b").resultDigest shouldBe base.resultDigest
    base.withResultDigest("b").checkpointId shouldBe base.checkpointId
  }

  "ThreadOwner" should "set each field through its own setter" in {
    val base = ThreadOwner(None, None)
    base.withTenantId("b").tenantId shouldBe Some("b")
    base.withTenantId(Some("b")).withTenantId(None).tenantId shouldBe None
    base.withParent(ThreadId("b")).parent shouldBe Some(ThreadId("b"))
    base.withParent(Some(ThreadId("b"))).withParent(None).parent shouldBe None
    base.withTenantId("b").parent shouldBe base.parent
    base.withParent(ThreadId("b")).tenantId shouldBe base.tenantId
  }

  "ChildThread" should "set each field through its own setter" in {
    val base = ChildThread(ThreadId("a"), originA, CheckpointStatus.Running, "a", Instant.EPOCH)
    base.withThreadId(ThreadId("b")).threadId shouldBe ThreadId("b")
    base.withOrigin(originB).origin shouldBe originB
    base.withStatus(CheckpointStatus.Suspended).status shouldBe CheckpointStatus.Suspended
    base.withLatest("b").latest shouldBe "b"
    base.withUpdatedAt(Instant.EPOCH.plusSeconds(7)).updatedAt shouldBe Instant.EPOCH.plusSeconds(7)
    base.withThreadId(ThreadId("b")).updatedAt shouldBe base.updatedAt
    base.withUpdatedAt(Instant.EPOCH.plusSeconds(7)).threadId shouldBe base.threadId
  }

  "GraphSnapshot" should "set each field through its own setter" in {
    val base =
      GraphSnapshot("a", "a", "a", 1, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false, None)
    base.withGraphId("b").graphId shouldBe "b"
    base.withGraphVersion("b").graphVersion shouldBe "b"
    base.withFingerprint("b").fingerprint shouldBe "b"
    base.withSuperstep(7).superstep shouldBe 7
    base.withState(Map("k" -> VersionedJson(1, ujson.Null))).state shouldBe Map("k" -> VersionedJson(1, ujson.Null))
    base
      .withFrontier(
        Vector(GraphSnapshot.PendingTask("1.0", "node", VersionedJson(1, ujson.Null), None, None, None, None))
      )
      .frontier shouldBe Vector(
      GraphSnapshot.PendingTask("1.0", "node", VersionedJson(1, ujson.Null), None, None, None, None)
    )
    base.withStaticJoins(Vector(GraphSnapshot.StaticArrivals("j", Vector("a")))).staticJoins shouldBe Vector(
      GraphSnapshot.StaticArrivals("j", Vector("a"))
    )
    base
      .withDynamicJoins(Vector(GraphSnapshot.Activation("j", "1.0", Vector("1.1"), Vector.empty)))
      .dynamicJoins shouldBe Vector(GraphSnapshot.Activation("j", "1.0", Vector("1.1"), Vector.empty))
    base
      .withParked(
        Vector(GraphSnapshot.ParkedContinuation("0.0", "node", VersionedJson(1, ujson.Null), "0.0", "node", None, None))
      )
      .parked shouldBe Vector(
      GraphSnapshot.ParkedContinuation("0.0", "node", VersionedJson(1, ujson.Null), "0.0", "node", None, None)
    )
    base.withPaused(true).paused shouldBe true
    base
      .withShape(GraphShape("entry", Vector("entry"), Map.empty, Vector.empty, Map.empty, Vector.empty, Vector.empty))
      .shape shouldBe Some(
      GraphShape("entry", Vector("entry"), Map.empty, Vector.empty, Map.empty, Vector.empty, Vector.empty)
    )
    base
      .withShape(
        Some(GraphShape("entry", Vector("entry"), Map.empty, Vector.empty, Map.empty, Vector.empty, Vector.empty))
      )
      .withShape(None)
      .shape shouldBe None
    base.withGraphId("b").shape shouldBe base.shape
    base
      .withShape(GraphShape("entry", Vector("entry"), Map.empty, Vector.empty, Map.empty, Vector.empty, Vector.empty))
      .graphId shouldBe base.graphId
  }

  "GraphSnapshot.PendingTask" should "set each field through its own setter" in {
    val base = GraphSnapshot.PendingTask(
      "a",
      "a",
      VersionedJson(1, ujson.Null),
      None,
      None,
      None,
      None,
      false,
      None,
      "a",
      None,
      None
    )
    base.withTaskId("b").taskId shouldBe "b"
    base.withNodeId("b").nodeId shouldBe "b"
    base.withInput(VersionedJson(2, ujson.Str("b"))).input shouldBe VersionedJson(2, ujson.Str("b"))
    base.withJoinId("b").joinId shouldBe Some("b")
    base.withJoinId(Some("b")).withJoinId(None).joinId shouldBe None
    base.withFanOutTask("b").fanOutTask shouldBe Some("b")
    base.withFanOutTask(Some("b")).withFanOutTask(None).fanOutTask shouldBe None
    base.withOriginTask("b").originTask shouldBe Some("b")
    base.withOriginTask(Some("b")).withOriginTask(None).originTask shouldBe None
    base.withOriginNode("b").originNode shouldBe Some("b")
    base.withOriginNode(Some("b")).withOriginNode(None).originNode shouldBe None
    base.withPassedBefore(true).passedBefore shouldBe true
    base.withReplay(Vector(EncodedRoute.Goto("node"))).replay shouldBe Some(Vector(EncodedRoute.Goto("node")))
    base.withReplay(Some(Vector(EncodedRoute.Goto("node")))).withReplay(None).replay shouldBe None
    base.withLineage("b").lineage shouldBe "b"
    base.withOriginLineage("b").originLineage shouldBe Some("b")
    base.withOriginLineage(Some("b")).withOriginLineage(None).originLineage shouldBe None
    base.withResumeDigest("b").resumeDigest shouldBe Some("b")
    base.withResumeDigest(Some("b")).withResumeDigest(None).resumeDigest shouldBe None
    base.withTaskId("b").resumeDigest shouldBe base.resumeDigest
    base.withResumeDigest("b").taskId shouldBe base.taskId
  }

  "GraphSnapshot.Activation" should "set each field through its own setter" in {
    val base = GraphSnapshot.Activation("a", "a", Vector.empty, Vector.empty)
    base.withJoinId("b").joinId shouldBe "b"
    base.withFanOutTask("b").fanOutTask shouldBe "b"
    base.withExpected(Vector("b")).expected shouldBe Vector("b")
    base.withArrived(Vector("b")).arrived shouldBe Vector("b")
    base.withJoinId("b").arrived shouldBe base.arrived
    base.withArrived(Vector("b")).joinId shouldBe base.joinId
  }

  "GraphSnapshot.ParkedContinuation" should "set each field through its own setter" in {
    val base = GraphSnapshot.ParkedContinuation(
      "a",
      "a",
      VersionedJson(1, ujson.Null),
      "a",
      "a",
      None,
      None,
      None,
      Vector.empty,
      "a",
      None,
      None
    )
    base.withInterruptId("b").interruptId shouldBe "b"
    base.withResumeNode("b").resumeNode shouldBe "b"
    base.withQuestion(VersionedJson(2, ujson.Str("b"))).question shouldBe VersionedJson(2, ujson.Str("b"))
    base.withOriginTask("b").originTask shouldBe "b"
    base.withOriginNode("b").originNode shouldBe "b"
    base.withJoinId("b").joinId shouldBe Some("b")
    base.withJoinId(Some("b")).withJoinId(None).joinId shouldBe None
    base.withFanOutTask("b").fanOutTask shouldBe Some("b")
    base.withFanOutTask(Some("b")).withFanOutTask(None).fanOutTask shouldBe None
    base.withBreakpoint(BreakpointPhase.Before).breakpoint shouldBe Some(BreakpointPhase.Before)
    base.withBreakpoint(Some(BreakpointPhase.Before)).withBreakpoint(None).breakpoint shouldBe None
    base.withHeldRoutes(Vector(EncodedRoute.Goto("node"))).heldRoutes shouldBe Vector(EncodedRoute.Goto("node"))
    base.withOriginLineage("b").originLineage shouldBe "b"
    base.withQuestionDigest("b").questionDigest shouldBe Some("b")
    base.withQuestionDigest(Some("b")).withQuestionDigest(None).questionDigest shouldBe None
    base.withResumeDigest("b").resumeDigest shouldBe Some("b")
    base.withResumeDigest(Some("b")).withResumeDigest(None).resumeDigest shouldBe None
    base.withInterruptId("b").resumeDigest shouldBe base.resumeDigest
    base.withResumeDigest("b").interruptId shouldBe base.interruptId
  }

  "PendingInterrupt" should "set each field through its own setter" in {
    val base = PendingInterrupt(InterruptId("a"), NodeId("a"), ujson.Null, None)
    base.withId(InterruptId("b")).id shouldBe InterruptId("b")
    base.withResumeNode(NodeId("b")).resumeNode shouldBe NodeId("b")
    base.withQuestion(ujson.Str("b")).question shouldBe ujson.Str("b")
    base.withBreakpoint(BreakpointPhase.Before).breakpoint shouldBe Some(BreakpointPhase.Before)
    base.withBreakpoint(Some(BreakpointPhase.Before)).withBreakpoint(None).breakpoint shouldBe None
    base.withId(InterruptId("b")).breakpoint shouldBe base.breakpoint
    base.withBreakpoint(BreakpointPhase.Before).id shouldBe base.id
  }

  "Command" should "set each field through its own setter" in {
    val base = Command(StateUpdate.empty, Nil)
    base.withUpdate(updateB).update shouldBe updateB
    base.withRoutes(Nil).routes shouldBe Nil
    base.withUpdate(updateB).routes shouldBe base.routes
    base.withRoutes(Nil).update shouldBe base.update
  }

  "GraphShape" should "set each field through its own setter" in {
    val base = GraphShape("a", Vector.empty, Map.empty, Vector.empty, Map.empty, Vector.empty, Vector.empty)
    base.withEntry("b").entry shouldBe "b"
    base.withNodes(Vector("b")).nodes shouldBe Vector("b")
    base.withEdges(Map("k" -> Vector("v"))).edges shouldBe Map("k" -> Vector("v"))
    base.withStaticJoins(Vector(GraphShape.StaticJoin("j", Vector("a"), "b"))).staticJoins shouldBe Vector(
      GraphShape.StaticJoin("j", Vector("a"), "b")
    )
    base.withDynamicJoins(Map("k" -> "v")).dynamicJoins shouldBe Map("k" -> "v")
    base.withResumes(Vector("b")).resumes shouldBe Vector("b")
    base.withKeys(Vector("b")).keys shouldBe Vector("b")
    base.withEntry("b").keys shouldBe base.keys
    base.withKeys(Vector("b")).entry shouldBe base.entry
  }

  "Observer" should "set each field through its own setter" in {
    val base = Observer(1, listenerA, false)
    base.withCapacity(7).capacity shouldBe 7
    base.withListener(listenerB).listener shouldBe listenerB
    base.withNested(true).nested shouldBe true
    base.withCapacity(7).nested shouldBe base.nested
    base.withNested(true).capacity shouldBe base.capacity
  }

  "EventRecord" should "set each field through its own setter" in {
    val base = EventRecord("a", 1L, "a", FencingToken(1L), None, None, None, Instant.EPOCH, RunEvent.RunCompleted)
    base.withThreadId("b").threadId shouldBe "b"
    base.withSeq(7L).seq shouldBe 7L
    base.withRunId("b").runId shouldBe "b"
    base.withToken(FencingToken(7L)).token shouldBe FencingToken(7L)
    base.withCheckpointId("b").checkpointId shouldBe Some("b")
    base.withCheckpointId(Some("b")).withCheckpointId(None).checkpointId shouldBe None
    base.withTaskId("b").taskId shouldBe Some("b")
    base.withTaskId(Some("b")).withTaskId(None).taskId shouldBe None
    base.withNodeId("b").nodeId shouldBe Some("b")
    base.withNodeId(Some("b")).withNodeId(None).nodeId shouldBe None
    base.withTimestamp(Instant.EPOCH.plusSeconds(7)).timestamp shouldBe Instant.EPOCH.plusSeconds(7)
    base.withEvent(RunEvent.TaskCompleted).event shouldBe RunEvent.TaskCompleted
    base.withThreadId("b").event shouldBe base.event
    base.withEvent(RunEvent.TaskCompleted).threadId shouldBe base.threadId
  }

  "EventDraft" should "set each field through its own setter" in {
    val base = EventDraft("a", None, None, None, Instant.EPOCH, RunEvent.RunCompleted)
    base.withRunId("b").runId shouldBe "b"
    base.withCheckpointId("b").checkpointId shouldBe Some("b")
    base.withCheckpointId(Some("b")).withCheckpointId(None).checkpointId shouldBe None
    base.withTaskId("b").taskId shouldBe Some("b")
    base.withTaskId(Some("b")).withTaskId(None).taskId shouldBe None
    base.withNodeId("b").nodeId shouldBe Some("b")
    base.withNodeId(Some("b")).withNodeId(None).nodeId shouldBe None
    base.withTimestamp(Instant.EPOCH.plusSeconds(7)).timestamp shouldBe Instant.EPOCH.plusSeconds(7)
    base.withEvent(RunEvent.TaskCompleted).event shouldBe RunEvent.TaskCompleted
    base.withRunId("b").event shouldBe base.event
    base.withEvent(RunEvent.TaskCompleted).runId shouldBe base.runId
  }

  "GraphError.CheckpointWriteFailed" should "set each field through its own setter" in {
    val base = GraphError.CheckpointWriteFailed("a", errorA, None)
    base.withThreadId("b").threadId shouldBe "b"
    base.withCause(errorB).cause shouldBe errorB
    base.withRunError(errorB).runError shouldBe Some(errorB)
    base.withRunError(Some(errorB)).withRunError(None).runError shouldBe None
    base.withThreadId("b").runError shouldBe base.runError
    base.withRunError(errorB).threadId shouldBe base.threadId
  }

  "GraphError.ThreadBusy" should "set each field through its own setter" in {
    val base = GraphError.ThreadBusy("a", None, GraphError.BusyHolder.Unnamed)
    base.withThreadId("b").threadId shouldBe "b"
    base.withLatestCheckpoint("b").latestCheckpoint shouldBe Some("b")
    base.withLatestCheckpoint(Some("b")).withLatestCheckpoint(None).latestCheckpoint shouldBe None
    base.withHolder(GraphError.BusyHolder.Lapsed).holder shouldBe GraphError.BusyHolder.Lapsed
    base.withThreadId("b").holder shouldBe base.holder
    base.withHolder(GraphError.BusyHolder.Lapsed).threadId shouldBe base.threadId
  }

  "TurnState" should "set each field through its own setter" in {
    val base = TurnState(1, None, None, None, Map.empty, false)
    base.withSteps(7).steps shouldBe 7
    base.withOutcome(TurnOutcome.Truncated).outcome shouldBe Some(TurnOutcome.Truncated)
    base.withOutcome(Some(TurnOutcome.Truncated)).withOutcome(None).outcome shouldBe None
    base.withStartAgent(AgentId.unsafe("b")).startAgent shouldBe Some(AgentId.unsafe("b"))
    base.withStartAgent(Some(AgentId.unsafe("b"))).withStartAgent(None).startAgent shouldBe None
    base.withStartTransfer(Transfer(AgentId.unsafe("a"), AgentId.unsafe("b"), "m1")).startTransfer shouldBe Some(
      Transfer(AgentId.unsafe("a"), AgentId.unsafe("b"), "m1")
    )
    base
      .withStartTransfer(Some(Transfer(AgentId.unsafe("a"), AgentId.unsafe("b"), "m1")))
      .withStartTransfer(None)
      .startTransfer shouldBe None
    base.withPriorValues(Map("k" -> PriorValue(None, None))).priorValues shouldBe Map("k" -> PriorValue(None, None))
    base.withCut(true).cut shouldBe true
    base.withSteps(7).cut shouldBe base.cut
    base.withCut(true).steps shouldBe base.steps
  }

  "Transfer" should "set each field through its own setter" in {
    val base = Transfer(AgentId.unsafe("a"), AgentId.unsafe("a"), "a")
    base.withSource(AgentId.unsafe("b")).source shouldBe AgentId.unsafe("b")
    base.withTarget(AgentId.unsafe("b")).target shouldBe AgentId.unsafe("b")
    base.withMessageId("b").messageId shouldBe "b"
    base.withSource(AgentId.unsafe("b")).messageId shouldBe base.messageId
    base.withMessageId("b").source shouldBe base.source
  }

  "TurnOutput" should "set each field through its own setter" in {
    val base = TurnOutput(TurnOutcome.Completed, AgentId.unsafe("a"))
    base.withOutcome(TurnOutcome.Truncated).outcome shouldBe TurnOutcome.Truncated
    base.withActiveAgent(AgentId.unsafe("b")).activeAgent shouldBe AgentId.unsafe("b")
    base.withOutcome(TurnOutcome.Truncated).activeAgent shouldBe base.activeAgent
    base.withActiveAgent(AgentId.unsafe("b")).outcome shouldBe base.outcome
  }

  "LoopHandoff" should "set each field through its own setter" in {
    val base = LoopHandoff(AgentId.unsafe("a"), None, false)
    base.withTarget(AgentId.unsafe("b")).target shouldBe AgentId.unsafe("b")
    base.withReason("b").reason shouldBe Some("b")
    base.withReason(Some("b")).withReason(None).reason shouldBe None
    base.withPreserveContext(true).preserveContext shouldBe true
    base.withTarget(AgentId.unsafe("b")).preserveContext shouldBe base.preserveContext
    base.withPreserveContext(true).target shouldBe base.target
  }

  "AgentInput" should "set each field through its own setter" in {
    val base = AgentInput("a", Vector.empty)
    base.withQuery("b").query shouldBe "b"
    base.withHistory(Vector(UserMessage("hi"))).history shouldBe Vector(UserMessage("hi"))
    base.withQuery("b").history shouldBe base.history
    base.withHistory(Vector(UserMessage("hi"))).query shouldBe base.query
  }

  "ToolTask" should "set each field through its own setter" in {
    val base = ToolTask("a", ToolCall("c1", "search", ujson.Obj()), IdempotencyKey("a"))
    base.withAssistantMessageId("b").assistantMessageId shouldBe "b"
    base.withCall(ToolCall("c2", "fetch", ujson.Obj())).call shouldBe ToolCall("c2", "fetch", ujson.Obj())
    base.withIdempotencyKey(IdempotencyKey("b")).idempotencyKey shouldBe IdempotencyKey("b")
    base.withAssistantMessageId("b").idempotencyKey shouldBe base.idempotencyKey
    base.withIdempotencyKey(IdempotencyKey("b")).assistantMessageId shouldBe base.assistantMessageId
  }

  "InjectionPattern" should "set each field through its own setter" in {
    val base = InjectionPattern("a", regexA, InjectionCategory.Jailbreak, 1)
    base.withName("b").name shouldBe "b"
    base.withRegex(regexB).regex shouldBe regexB
    base.withCategory(InjectionCategory.CodeInjection).category shouldBe InjectionCategory.CodeInjection
    base.withSeverity(7).severity shouldBe 7
    base.withName("b").severity shouldBe base.severity
    base.withSeverity(7).name shouldBe base.name
  }
}
