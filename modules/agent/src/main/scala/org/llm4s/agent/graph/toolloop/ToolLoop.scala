package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.{ AgentId, AgentNode }
import org.llm4s.agent.events
import org.llm4s.agent.events.{ AgentEvents, GuardrailBlock, GuardrailPhase, ToolExecutionOutcome }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{
  GuardrailBlocked,
  MiddlewareAsked,
  MiddlewareId,
  MiddlewareStack,
  ModelRequest,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome, ToolQuestion, ToolSet }
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.duration.{ Duration, DurationLong, FiniteDuration }
import scala.util.{ Failure, Success, Try }

/** Who asked for an approval: the tool itself, or a middleware's tool wrapper. All resume at the same approval node. */
enum ApprovalSource derives ReadWriter:
  case Tool
  case Middleware(id: MiddlewareId)

/**
 * The question an approval interrupt asks: one tool call, from one assistant message. `answered` carries
 * the answers middleware questions of the call were given before it asked, so its chain runs again
 * with them once it is approved.
 */
final case class ApprovalRequest private (
  assistantMessageId: String,
  call: ToolCall,
  reason: String,
  source: ApprovalSource,
  answered: Vector[GivenAnswer] = Vector.empty
) derives ReadWriter:
  def withAssistantMessageId(id: String): ApprovalRequest =
    ApprovalRequest(id, call, reason, source, answered)
  def withCall(c: ToolCall): ApprovalRequest = ApprovalRequest(assistantMessageId, c, reason, source, answered)
  def withReason(r: String): ApprovalRequest = ApprovalRequest(assistantMessageId, call, r, source, answered)
  def withSource(s: ApprovalSource): ApprovalRequest =
    ApprovalRequest(assistantMessageId, call, reason, s, answered)
  def withAnswered(a: Vector[GivenAnswer]): ApprovalRequest =
    ApprovalRequest(assistantMessageId, call, reason, source, a)

object ApprovalRequest:
  def apply(
    assistantMessageId: String,
    call: ToolCall,
    reason: String,
    source: ApprovalSource,
    answered: Vector[GivenAnswer] = Vector.empty
  ): ApprovalRequest = new ApprovalRequest(assistantMessageId, call, reason, source, answered)

/** A reviewer's answer. `Edit` runs the call with new arguments, recording them in the assistant message first. */
enum ApprovalDecision derives ReadWriter:
  case Approve
  case Edit(arguments: ujson.Value)
  case Reject(reason: String)

/** One model-issued tool call, scheduled as its own task. */
final case class ToolTask(assistantMessageId: String, call: ToolCall) derives ReadWriter

/**
 * The question a tool's `Ask` interrupt carries: the call that asked, from one assistant message,
 * the question encoded with the tool's declared question codec, and whether the call was approved
 * before it asked - `resume` sees the same `ToolContext.approved`. Find it with
 * [[ToolLoop.questions]], read its question as the tool's type with [[ToolLoop.question]], and
 * answer it with [[ToolLoop.answer]].
 */
final case class ToolQuestionRequest private (
  assistantMessageId: String,
  call: ToolCall,
  question: ujson.Value,
  approved: Boolean = false,
  answered: Vector[GivenAnswer] = Vector.empty
) derives ReadWriter:
  def withAssistantMessageId(id: String): ToolQuestionRequest =
    ToolQuestionRequest(id, call, question, approved, answered)
  def withCall(c: ToolCall): ToolQuestionRequest =
    ToolQuestionRequest(assistantMessageId, c, question, approved, answered)
  def withQuestion(q: ujson.Value): ToolQuestionRequest =
    ToolQuestionRequest(assistantMessageId, call, q, approved, answered)
  def withApproved(a: Boolean): ToolQuestionRequest =
    ToolQuestionRequest(assistantMessageId, call, question, a, answered)
  def withAnswered(a: Vector[GivenAnswer]): ToolQuestionRequest =
    ToolQuestionRequest(assistantMessageId, call, question, approved, a)

object ToolQuestionRequest:
  def apply(
    assistantMessageId: String,
    call: ToolCall,
    question: ujson.Value,
    approved: Boolean = false,
    answered: Vector[GivenAnswer] = Vector.empty
  ): ToolQuestionRequest = new ToolQuestionRequest(assistantMessageId, call, question, approved, answered)

/** The hook a middleware asked its question from. */
enum MiddlewareHook derives ReadWriter:
  case BeforeAgent, WrapModelCall, WrapToolCall, AfterAgent

/**
 * The answer a middleware question was given: kept while the hook's stack runs again, so that the
 * middleware - `middleware` of agent `agent`'s stack - finds it with `AgentMiddleware.Asking.answered`.
 */
final case class GivenAnswer private (
  agent: AgentId,
  middleware: MiddlewareId,
  question: ujson.Value,
  answer: ujson.Value
) derives ReadWriter:
  def withAgent(a: AgentId): GivenAnswer           = GivenAnswer(a, middleware, question, answer)
  def withMiddleware(m: MiddlewareId): GivenAnswer = GivenAnswer(agent, m, question, answer)
  def withQuestion(q: ujson.Value): GivenAnswer    = GivenAnswer(agent, middleware, q, answer)
  def withAnswer(a: ujson.Value): GivenAnswer      = GivenAnswer(agent, middleware, question, a)

object GivenAnswer:
  def apply(agent: AgentId, middleware: MiddlewareId, question: ujson.Value, answer: ujson.Value): GivenAnswer =
    new GivenAnswer(agent, middleware, question, answer)

/**
 * The question a middleware interrupt asks ([[org.llm4s.agent.graph.middleware.AgentMiddleware.Asking]]):
 * `middleware` of agent `agent`'s stack asked `question`, encoded with its declared question codec, from
 * `hook`. Read it as the middleware's type with [[ToolLoop.middlewareQuestion]]; answer it with the
 * middleware's answer type.
 *
 * The rest is what the hook runs again with once answered: the turn's `input` (`BeforeAgent`); the tool
 * `call` and whether it was `approved` (`WrapToolCall`); and the answers other questions of the same
 * hook run were `answered` with.
 */
final case class MiddlewareQuestionRequest private (
  agent: AgentId,
  middleware: MiddlewareId,
  hook: MiddlewareHook,
  question: ujson.Value,
  input: Option[AgentInput] = None,
  call: Option[ToolTask] = None,
  approved: Boolean = false,
  answered: Vector[GivenAnswer] = Vector.empty
) derives ReadWriter:
  def withAgent(a: AgentId): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(a, middleware, hook, question, input, call, approved, answered)
  def withMiddleware(m: MiddlewareId): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, m, hook, question, input, call, approved, answered)
  def withHook(h: MiddlewareHook): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, h, question, input, call, approved, answered)
  def withQuestion(q: ujson.Value): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, hook, q, input, call, approved, answered)
  def withInput(i: AgentInput): MiddlewareQuestionRequest = withInput(Some(i))
  def withInput(i: Option[AgentInput]): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, hook, question, i, call, approved, answered)
  def withCall(c: ToolTask): MiddlewareQuestionRequest = withCall(Some(c))
  def withCall(c: Option[ToolTask]): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, hook, question, input, c, approved, answered)
  def withApproved(a: Boolean): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, hook, question, input, call, a, answered)
  def withAnswered(a: Vector[GivenAnswer]): MiddlewareQuestionRequest =
    MiddlewareQuestionRequest(agent, middleware, hook, question, input, call, approved, a)

object MiddlewareQuestionRequest:
  def apply(
    agent: AgentId,
    middleware: MiddlewareId,
    hook: MiddlewareHook,
    question: ujson.Value,
    input: Option[AgentInput] = None,
    call: Option[ToolTask] = None,
    approved: Boolean = false,
    answered: Vector[GivenAnswer] = Vector.empty
  ): MiddlewareQuestionRequest =
    new MiddlewareQuestionRequest(agent, middleware, hook, question, input, call, approved, answered)

/**
 * A task a static breakpoint holds, as an agent reports it: its `node` (`<agent>/model`,
 * `<agent>/call-tool`, `<agent>/finish`; after it ran, also the approval, question and middleware-question
 * nodes that continue those steps; or any other a run's `RunConfig` names), the `phase`, and - for a tool
 * call held before it runs - the `call`. Answer it with `org.llm4s.agent.graph.Breakpoint.proceed`.
 */
final case class BreakpointRequest private (node: NodeId, phase: BreakpointPhase, call: Option[ToolCall]):
  def withNode(n: NodeId): BreakpointRequest           = BreakpointRequest(n, phase, call)
  def withPhase(p: BreakpointPhase): BreakpointRequest = BreakpointRequest(node, p, call)
  def withCall(c: ToolCall): BreakpointRequest         = withCall(Some(c))
  def withCall(c: Option[ToolCall]): BreakpointRequest = BreakpointRequest(node, phase, c)

object BreakpointRequest:
  def apply(node: NodeId, phase: BreakpointPhase, call: Option[ToolCall] = None): BreakpointRequest =
    new BreakpointRequest(node, phase, call)

/** The result the loop wrote for one call, waiting for the batch barrier. */
final case class ToolResult(assistantMessageId: String, toolCallId: String, content: String, isError: Boolean)
    derives ReadWriter

/** One invocation of an agent's model: the task's [[RunContext]], the agent, and this attempt's number (from 1). */
final class ModelCall private[toolloop] (val context: RunContext, val agent: AgentId, val attempt: Int):
  def textDelta(text: String): Unit = AgentEvents.TextDelta.progress(context, events.TextDelta(attempt, text))
  def thinkingDelta(text: String): Unit =
    AgentEvents.ThinkingDelta.progress(context, events.ThinkingDelta(attempt, text))

/** The model call: the conversation so far, and the tools on offer, to the model's completion. */
trait ModelStep:
  def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion]

object ModelStep:

  /**
   * Calls `client` with `options`, its `tools` replaced by the loop's
   * [[org.llm4s.agent.graph.tool.ToolSet.toolFunctions]]. With `streaming`, it calls
   * `streamComplete`, sending each chunk's text as a `TextDelta` and its thinking as a
   * `ThinkingDelta`, and returns the accumulated completion; otherwise `complete`.
   */
  def fromClient(
    client: LLMClient,
    options: CompletionOptions = CompletionOptions(),
    streaming: Boolean = false
  ): ModelStep =
    (messages, tools, call) =>
      val withTools = options.withTools(tools.toolFunctions)
      if !streaming then client.complete(Conversation(messages), withTools)
      else
        client.streamComplete(
          Conversation(messages),
          withTools,
          chunk =>
            chunk.thinkingDelta.filter(_.nonEmpty).foreach(call.thinkingDelta)
            chunk.content.filter(_.nonEmpty).foreach(call.textDelta)
        )

/**
 * The model/tool loop of an agent family as one graph - the Stage 0 proof of runtime-owned tool
 * results and resumable approval ((#1269)), running [[org.llm4s.agent.graph.tool.AgentTool]]s
 * ((#1278)), for every [[LoopAgent]] of a family ((#1328)). Each agent's nodes are prefixed with
 * its id; `input` is the one shared entry:
 *
 * {{{
 * input -> <id>/model --fan-out, one task per call--> <id>/call-tool --(join "<id>/tool-batch")--> <id>/collect -> <id>/model
 *              \                                          \--suspend--> <id>/approval  --/
 *               \--final answer--> <id>/finish             \--suspend--> <id>/ask/<tool> --/
 * }}}
 *
 *  - A turn takes an [[AgentInput]]. On a new thread `input` imports its `history` - no system
 *    message, and a valid conversation - and makes the root the active agent; on an existing thread
 *    a `history` is refused. It then runs the root's `beforeAgent` stack on the query and, when
 *    another agent is active, that agent's stack on the result, appends the user message, resets
 *    [[LoopKeys.turn]] and routes to the active agent's model. A `Left` from either stack, or a
 *    blank query, blocks the run without the query or a model call; a new thread still keeps its
 *    imported history and the root as its active agent. The turn's [[TurnOutput]] is its outcome
 *    and the active agent.
 *  - `<id>/model` counts the turn's steps: at the agent's `maxSteps` it ends the turn with
 *    [[TurnOutcome.StepLimitReached]] without calling the model. Otherwise it sends the agent's
 *    system prompt, then the history; the prompt is never stored.
 *  - The middleware stack (#1279) runs at the run's boundaries and around each call: `input` runs
 *    every `beforeAgent` on the user's text, `model` runs `ModelStep.next` inside every
 *    `wrapModelCall`, and `finish` runs every `afterAgent` (in reverse) on the final answer,
 *    replacing the stored answer's content when it changes. The root's boundary hooks guard the
 *    whole family: `input` runs the root's `beforeAgent` stack, then the active agent's, and
 *    `<id>/finish` of a handoff target runs its own `afterAgent` stack, then the root's. A `Left`
 *    from either hook, a blank query from `beforeAgent` and a blank answer from `afterAgent`
 *    *block* the run (design 4.13): it ends as a finished failure, `RunResult.Failed`, carrying
 *    that error, and the thread stays usable; a cancellation is not a Block. An input Block
 *    commits only a new thread's seed - its imported history and the root as active agent - and an
 *    output Block removes the blocked turn from the history, restoring the agent and transfer the
 *    turn started with. `wrapModelCall` and `wrapToolCall` stay per agent. A middleware's `tools`
 *    join the loop's [[org.llm4s.agent.graph.tool.ToolSet]], and its
 *    `writes` are keys its `wrapToolCall` may add to a `Success` update.
 *  - Each call is its own task. Its tool is looked up, its raw arguments validated against the
 *    tool's `argumentSchema`, decoded and checked by the tool's `validateDecoded` - any failure is an
 *    error result the model sees, before any middleware or the tool runs. Then the middleware
 *    stack's `wrapToolCall` chain, the first middleware outermost, with the tool at its centre.
 *  - A `NeedsApproval`, from a wrapper or the tool, suspends the call with an [[ApprovalRequest]]
 *    naming its [[ApprovalSource]], and resumes at the agent's `approval` node. `Approve` and `Edit`
 *    check the arguments again (an edit's are new) and run the whole chain again with
 *    `ToolContext.approved`, so a deny rule still refuses an edit; a second `NeedsApproval` from an
 *    approved call is an error result. A tool's `Ask` suspends with a [[ToolQuestionRequest]] at
 *    that tool's `ask/<name>` node, which runs the tool's `resume` with the decoded answer inside the
 *    same chain, with the approval the call asked with.
 *  - A middleware extending `AgentMiddleware.Asking` asks a typed question (#1704): from `beforeAgent` (the
 *    `input` node), `wrapModelCall` (`<id>/model`), `afterAgent` (`<id>/finish`) or `wrapToolCall` (each call).
 *    The asking task suspends, storing nothing but the usage of model calls a wrapper made before asking, at
 *    `<id>/asked/<middleware>/<hook>` with a [[MiddlewareQuestionRequest]]; once answered, it runs again with
 *    every answer given so far, so the hook's stack runs again from the outermost middleware and the asking one
 *    continues ([[GivenAnswer]]).
 *  - The loop, not the tool, records exactly one [[ToolResult]] per call - for success, failure,
 *    denial, rejection and unknown tools alike, however often a wrapper ran the tool; the results key
 *    refuses a second one. A tool's thrown exception is that call's error result. `Fatal`, an update
 *    to a key neither the tool nor any middleware declares, and a question it does not declare fail the run with
 *    `GraphError.ToolFailed`; a throwing wrapper fails it with `GraphError.MiddlewareFailed`. Either
 *    leaves the checkpoint `Running`, so `recover` re-runs only that call.
 *  - `collect` runs only when the barrier releases, checks every call of the batch has exactly one
 *    result, and appends the `ToolMessage`s in call order. The model never sees a partial batch.
 *  - The model node re-checks the history with `Message.validateConversation` before every call.
 *  - Each [[LoopHandoff]] is offered to the agent's model as a stand-in tool `handoff_to_<target>`
 *    ([[HandoffTools]]), which call-tool never runs. When a handoff is an assistant message's only
 *    call, `<id>/model` stores the message and `ToolMessage("Transferred to <target>")`, makes the
 *    target the active agent and routes to `<target>/model`; the next turn starts there too. A
 *    handoff with other calls, or two handoffs (to one target or to several), is refused: every
 *    call gets an error result and the same agent is asked again. Both cost the step the model call
 *    took. The turn's steps are shared by every agent: each model node checks its own agent's
 *    `maxSteps` against the turn's count so far, so a target with a lower limit than its source can
 *    end the turn with [[TurnOutcome.StepLimitReached]] before its first call. The handoff call's
 *    `reason` argument is offered to the model but neither validated nor used for routing; the tool
 *    name alone picks the target. Each transfer is recorded in [[LoopKeys.transfer]]. With
 *    `preserveContext = false` the target is sent the last user message before the transfer, then
 *    the transfer's assistant message onwards; the history itself is untouched. A non-root agent
 *    with no recorded transfer to it fails the call rather than being sent the full history.
 */
final class ToolLoop private (
  val graph: CompiledGraph[AgentInput, TurnOutput],
  approval: ResumeRef[ApprovalRequest, ApprovalDecision],
  approvalNodes: Set[NodeId],
  askNodes: Set[NodeId],
  askedNodes: Set[NodeId],
  callToolNodes: Set[NodeId],
  continuations: Map[(AgentId, AgentNode), Set[NodeId]]
):

  /**
   * The nodes a breakpoint on `node` of agent `agent` holds in `phase`. Before, only the node itself: a
   * task continued after an approval or a question was just reviewed. After, every node whose task does
   * what the node does - so a step continued after a review is held after it ran, as the step is: `Model`
   * adds the agent's `wrapModelCall` question nodes; `Tool` its approval, tool-question and `wrapToolCall`
   * question nodes, which record a call's result; `Finish` its `afterAgent` question nodes and, for a
   * handoff target, the root's, where a root middleware's question about the target's answer continues.
   */
  private[agent] def breakpointNodes(agent: AgentId, node: AgentNode, phase: BreakpointPhase): Set[NodeId] =
    val own = node match
      case AgentNode.Model  => ToolLoop.modelNode(agent)
      case AgentNode.Tool   => ToolLoop.callToolNode(agent)
      case AgentNode.Finish => ToolLoop.finishNode(agent)
    phase match
      case BreakpointPhase.Before => Set(own)
      case BreakpointPhase.After  => continuations.getOrElse((agent, node), Set.empty) + own

  /** The approval requests a suspended run is waiting on, at any agent's approval node. */
  def requests(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ApprovalRequest)]] =
    pending[ApprovalRequest](suspended, approvalNodes.contains)

  /** The tool questions a suspended run is waiting on; read each one's question with [[ToolLoop.question]]. */
  def questions(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ToolQuestionRequest)]] =
    pending[ToolQuestionRequest](suspended, askNodes.contains)

  /**
   * The middleware questions a suspended run is waiting on; read each one's question with
   * [[ToolLoop.middlewareQuestion]].
   */
  def middlewareQuestions(suspended: RunResult.Suspended): Result[Vector[(InterruptId, MiddlewareQuestionRequest)]] =
    pending[MiddlewareQuestionRequest](suspended, askedNodes.contains)

  /** The tasks static breakpoints hold, in the order they were held; a tool call held before it runs names its call. */
  def breakpoints(suspended: RunResult.Suspended): Result[Vector[(InterruptId, BreakpointRequest)]] =
    suspended.interrupts.foldLeft[Result[Vector[(InterruptId, BreakpointRequest)]]](Right(Vector.empty)) { (acc, i) =>
      i.breakpoint.fold(acc) { phase =>
        val call =
          if phase == BreakpointPhase.Before && callToolNodes.contains(i.resumeNode) then
            Try(upickle.default.read[ToolTask](i.question)).toResult.map(task => Some(task.call))
          else Right(None)
        acc.flatMap(found => call.map(c => found :+ (i.id -> BreakpointRequest(i.resumeNode, phase, c))))
      }
    }

  /**
   * Answers for [[GraphRuntime.resume]] or [[CompiledGraph.resume]]. Every agent's approval node
   * takes the same answer codec, so a decision encodes the same whichever agent asked.
   */
  def answers(decisions: (InterruptId, ApprovalDecision)*): Map[InterruptId, ujson.Value] =
    decisions.map((id, decision) => id -> approval.answer(decision)).toMap

  /**
   * One question's answer, of the asking tool's answer type, to merge with [[answers]] into one
   * resume map. An answer that does not decode as the tool's type becomes that call's error result.
   */
  def answer[Ans: ReadWriter](id: InterruptId, answer: Ans): (InterruptId, ujson.Value) =
    id -> upickle.default.writeJs(answer)

  private def pending[Q: ReadWriter](
    suspended: RunResult.Suspended,
    at: NodeId => Boolean
  ): Result[Vector[(InterruptId, Q)]] =
    suspended.interrupts
      .filter(i => i.breakpoint.isEmpty && at(i.resumeNode))
      .foldLeft[Result[Vector[(InterruptId, Q)]]](Right(Vector.empty)) { (acc, i) =>
        acc.flatMap(found => Try(upickle.default.read[Q](i.question)).toResult.map(r => found :+ (i.id -> r)))
      }

object ToolLoop:

  /** The tool a durable `ToolExecuted` names for a call to a tool the agent does not have. */
  val UnknownTool: String = "<unknown>"

  /** A pending question, decoded as the asking tool's question type `Q`. */
  def question[Q: ReadWriter](request: ToolQuestionRequest): Result[Q] =
    Try(upickle.default.read[Q](request.question)).toResult

  /** A pending middleware question, decoded as the asking middleware's question type `Q`. */
  def middlewareQuestion[Q: ReadWriter](request: MiddlewareQuestionRequest): Result[Q] =
    Try(upickle.default.read[Q](request.question)).toResult

  /** Agent `agent`'s model node: a task per model call. */
  def modelNode(agent: AgentId): NodeId = NodeId(s"${agent.value}/model")

  /** Agent `agent`'s tool-call node: a task per tool call, so a breakpoint holds each call on its own. */
  def callToolNode(agent: AgentId): NodeId = NodeId(s"${agent.value}/call-tool")

  /** Agent `agent`'s finish node: the turn's final answer, before its `afterAgent` hooks run. */
  def finishNode(agent: AgentId): NodeId = NodeId(s"${agent.value}/finish")

  /** The results recorded for the current batch, at most one per call; removed when the batch is collected. */
  val results: StateKey[Vector[ToolResult], ToolResult] =
    StateKey[Vector[ToolResult], ToolResult]("tool-results", Vector.empty) { (recorded, result) =>
      if recorded.exists(r => r.assistantMessageId == result.assistantMessageId && r.toolCallId == result.toolCallId)
      then Left(ValidationError("tool-results", s"tool call '${result.toolCallId}' already has a result"))
      else Right(recorded :+ result)
    }

  /**
   * The loop for a family of `agents`, a new thread starting with `root`. Refuses an empty family,
   * a root outside it, a duplicate id, and any agent whose tools - its own and its middleware's -
   * do not form a valid set or declare a key the loop owns.
   */
  def build(id: String, version: String, root: AgentId, agents: Vector[LoopAgent]): Result[ToolLoop] =
    for
      _ <- familyValid(root, agents)
      prepared <- agents.foldLeft[Result[Vector[Prepared]]](Right(Vector.empty)) { (acc, agent) =>
        acc.flatMap(done => prepare(agent).map(done :+ _))
      }
      loop <- assemble(id, version, root, prepared)
    yield loop

  /**
   * An agent with its middleware stacked and its tools joined with the middleware's, checked. `tools`
   * are the ones call-tool may run; `offered` adds the handoff stand-ins, for the model only.
   */
  final private case class Prepared(agent: LoopAgent, tools: ToolSet, offered: ToolSet, stack: MiddlewareStack)

  private def familyValid(root: AgentId, agents: Vector[LoopAgent]): Result[Unit] =
    val ids = agents.map(_.id)
    val problems =
      Option.when(agents.isEmpty)("a family needs at least one agent").toVector ++
        Option.when(agents.nonEmpty && !ids.contains(root))(s"the root agent '${root.value}' is not in the family") ++
        ids.distinct.filter(i => ids.count(_ == i) > 1).map(i => s"duplicate agent id '${i.value}'") ++
        agents.flatMap(handoffProblems(_, ids.toSet))
    if problems.isEmpty then Right(()) else Left(ValidationError("tool loop", problems.toList))

  /**
   * A handoff must target another agent of the family, and an agent hands off to each target at
   * most once.
   */
  private def handoffProblems(agent: LoopAgent, family: Set[AgentId]): Vector[String] =
    val targets = agent.handoffs.map(_.target)
    targets.distinct.flatMap { target =>
      Option.when(target == agent.id)(s"agent '${agent.id.value}' hands off to itself") ++
        Option.when(!family.contains(target))(
          s"agent '${agent.id.value}' hands off to '${target.value}', which is not in the family"
        ) ++
        Option.when(targets.count(_ == target) > 1)(
          s"agent '${agent.id.value}' has more than one handoff to '${target.value}'"
        )
    }

  private def prepare(agent: LoopAgent): Result[Prepared] =
    for
      stack <- MiddlewareStack.of(agent.middleware*)
      // a middleware's tools join the user's, checked as one set: a name clash or invalid schema is refused
      toolSet <- ToolSet.of(agent.tools.validator, (agent.tools.tools ++ stack.tools)*)
      _       <- ownedKeysUntouched(agent.id, toolSet, stack)
      _       <- handoffNamesFree(agent.id, toolSet, agent.handoffs)
      offered <- ToolSet.of(toolSet.validator, (toolSet.tools ++ HandoffTools.tools(agent.handoffs))*)
    yield Prepared(agent, toolSet, offered, stack)

  /** A handoff's stand-in tool may not share a name with one of the agent's tools, or a middleware's. */
  private def handoffNamesFree(agent: AgentId, tools: ToolSet, handoffs: Vector[LoopHandoff]): Result[Unit] =
    handoffs.map(h => HandoffTools.toolName(h.target)).filter(tools.get(_).isDefined).toList match
      case Nil => Right(())
      case clashing =>
        Left(
          ValidationError(
            "tool loop",
            clashing.map(n => s"agent '${agent.value}': handoff tool '$n' clashes with a tool of the same name")
          )
        )

  /** The loop alone writes its keys: a tool or middleware writing one could break one result per call, or a turn. */
  private def ownedKeysUntouched(agent: AgentId, tools: ToolSet, stack: MiddlewareStack): Result[Unit] =
    val owned =
      Set(
        results.id,
        Messages.key.id,
        LoopKeys.usage.id,
        LoopKeys.activeAgent.id,
        LoopKeys.turn.id,
        LoopKeys.transfer.id
      )
    def touches(keys: Set[StateKey[?, ?]]) = keys.exists(k => owned.contains(k.id))
    val clause =
      "declares a key the loop owns ('tool-results', 'messages', 'usage', 'active-agent', 'turn' or 'transfer')"
    val byTools =
      tools.tools.filter(t => touches(t.writes)).map(t => s"agent '${agent.value}': tool '${t.spec.name}' $clause")
    val byMiddleware =
      stack.ordered
        .filter(m => touches(m.writes))
        .map(m => s"agent '${agent.value}': middleware '${m.id.value}' $clause")
    (byTools ++ byMiddleware).toList match
      case Nil      => Right(())
      case problems => Left(ValidationError("tool loop", problems))

  /** One agent's node handles, issued before any node is implemented so that agents can route to each other. */
  final private class AgentNodes(
    val prepared: Prepared,
    val model: NodeRef[Unit],
    val finish: NodeRef[Unit],
    val collect: NodeRef[Unit],
    val callTool: NodeRef[ToolTask],
    val approval: ResumeRef[ApprovalRequest, ApprovalDecision],
    val batch: DynamicJoin,
    val askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]],
    val askedRefs: Map[(MiddlewareId, MiddlewareHook), AskedRef[?]]
  ):
    def agent: LoopAgent             = prepared.agent
    def tools: ToolSet               = prepared.tools
    def offered: ToolSet             = prepared.offered
    def stack: MiddlewareStack       = prepared.stack
    def asking: Vector[AgentTool[?]] = tools.tools.filter(_.spec.question.isDefined)

    /** What the call-tool, approval and ask nodes may write: any tool's keys; each call is checked against its own tool's. */
    def callWrites: Set[StateKey[?, ?]] =
      tools.tools.flatMap(_.writes).toSet ++ stack.writes ++ Set(results, Messages.key)

  /**
   * The resume node of one middleware question, for one hook, with the middleware's answer codec: the
   * kernel decodes an answer with it, refusing one that does not decode, and the node encodes it again
   * for the stack.
   */
  final private class AskedRef[A](val ref: ResumeRef[MiddlewareQuestionRequest, A], answerCodec: ReadWriter[A]):
    def answerJson(answer: A): ujson.Value = upickle.default.writeJs(answer)(using answerCodec)
    def suspend(request: MiddlewareQuestionRequest, update: StateUpdate = StateUpdate.empty): NodeResult =
      NodeResult.Suspend(update, request, ref)

  /** What a node writes when it runs a middleware hook again: the model's and the finish's write sets. */
  private val modelWrites: Set[StateKey[?, ?]] =
    Set(Messages.key, LoopKeys.usage, LoopKeys.turn, LoopKeys.activeAgent, LoopKeys.transfer)
  private val finishWrites: Set[StateKey[?, ?]] =
    Set(Messages.key, LoopKeys.turn, LoopKeys.activeAgent, LoopKeys.transfer)
  private val inputWrites: Set[StateKey[?, ?]] = Set(Messages.key, LoopKeys.activeAgent, LoopKeys.turn)

  private def declare(b: GraphBuilder, prepared: Prepared): AgentNodes =
    val prefix  = s"${prepared.agent.id.value}/"
    val collect = b.declare[Unit](s"${prefix}collect")
    val askRefs = prepared.tools.tools
      .filter(_.spec.question.isDefined)
      .map(t => t.spec.name -> b.declareResume[ToolQuestionRequest, ujson.Value](s"${prefix}ask/${t.spec.name}"))
      .toMap
    // one resume node per asking middleware and hook, so each answer is decoded with that middleware's codec
    val askedRefs = prepared.stack.ordered.flatMap { m =>
      m.declaredQuestion.toVector.flatMap { case codecs: ToolQuestion[?, ans] =>
        MiddlewareHook.values.toVector.map { hook =>
          val nodeId = s"${prefix}asked/${m.id.value}/${hookName(hook)}"
          (m.id, hook) -> AskedRef[ans](
            b.declareResume[MiddlewareQuestionRequest, ans](nodeId)(using summon, codecs.answerCodec),
            codecs.answerCodec
          )
        }
      }
    }.toMap
    AgentNodes(
      prepared,
      model = b.declare[Unit](s"${prefix}model"),
      finish = b.declare[Unit](s"${prefix}finish"),
      collect = collect,
      callTool = b.declare[ToolTask](s"${prefix}call-tool"),
      approval = b.declareResume[ApprovalRequest, ApprovalDecision](s"${prefix}approval"),
      batch = b.dynamicJoin(s"${prefix}tool-batch", collect),
      askRefs = askRefs,
      askedRefs = askedRefs
    )

  private def hookName(hook: MiddlewareHook): String =
    val name = hook.toString
    s"${name.head.toLower}${name.tail}"

  /** What one agent's nodes run, for its middleware questions' resume nodes to run again with the answers given. */
  final private class AgentSteps(
    val model: (ThreadState, RunContext, Vector[GivenAnswer]) => NodeResult,
    val finish: (ThreadState, RunContext, Vector[GivenAnswer]) => NodeResult,
    val pipeline: Pipeline
  )

  /** `context` with the answers `owner`'s middleware were given: a stack sees only its own agent's. */
  private def answering(owner: AgentId, context: RunContext, answers: Vector[GivenAnswer]): RunContext =
    context.withAnswers(
      answers.filter(_.agent == owner).map(g => g.middleware.value -> (g.question, g.answer)).toMap
    )

  /**
   * Threads `text` through each owner's stack in order, each seeing the answers its own middleware were
   * given; a `Left` names the owner whose stack returned it.
   */
  private def threaded(owners: Vector[AgentNodes], text: String, context: RunContext, answers: Vector[GivenAnswer])(
    hook: (MiddlewareStack, String, RunContext) => Result[String]
  ): Either[(AgentNodes, LLMError), String] =
    owners.foldLeft[Either[(AgentNodes, LLMError), String]](Right(text)) { (acc, owner) =>
      acc.flatMap(t => hook(owner.stack, t, answering(owner.agent.id, context, answers)).left.map(owner -> _))
    }

  /**
   * Suspends the asking task at the resume node of `owner`'s middleware that asked, for `hook`. Nothing of
   * the step is committed - only `update`, the usage of model calls made before the question - and the
   * task runs again from the start once answered. A question from a middleware the stack does not hold
   * as asking - one asked through another middleware's instance - fails the run.
   */
  private def askedSuspend(
    owner: AgentNodes,
    asked: MiddlewareAsked,
    hook: MiddlewareHook,
    answers: Vector[GivenAnswer],
    input: Option[AgentInput] = None,
    update: StateUpdate = StateUpdate.empty
  ): NodeResult =
    owner.askedRefs.get((asked.middleware, hook)) match
      case Some(ref) =>
        ref.suspend(
          MiddlewareQuestionRequest(
            owner.agent.id,
            asked.middleware,
            hook,
            asked.question,
            input,
            None,
            false,
            answers
          ),
          update
        )
      case None =>
        NodeResult.Fail(
          ValidationError(
            "middleware question",
            s"middleware '${asked.middleware.value}' asked a question, and agent '${owner.agent.id.value}' has no asking middleware of that id"
          )
        )

  private def assemble(id: String, version: String, root: AgentId, prepared: Vector[Prepared]): Result[ToolLoop] =
    val messages = Messages.key
    val b        = GraphBuilder(id, version)
    val agents   = prepared.map(p => p.agent.id -> declare(b, p)).toMap
    // whether a transfer keeps the context, by (source, target)
    val preserves = prepared.flatMap(p => p.agent.handoffs.map(h => (p.agent.id, h.target) -> h.preserveContext)).toMap
    val steps     = prepared.map(p => p.agent.id -> implement(b, agents(p.agent.id), agents, root, preserves)).toMap

    // An input Block (a beforeAgent `Left`, or a blank query) stores nothing of the turn: the run ends as a
    // finished failure, committing only a new thread's seed - its imported history and the root as active agent.
    // A middleware question stores nothing either: the input runs again, with the answers, once it is answered.
    def inputStep(in: AgentInput, state: ThreadState, context: RunContext, answers: Vector[GivenAnswer]): NodeResult =
      val taskId = context.position.taskId.value
      val outcome = for
        active   <- state.get(LoopKeys.activeAgent)
        history  <- state.get(messages)
        transfer <- state.get(LoopKeys.transfer)
        start <- active match
          case None =>
            imported(in.history, history, taskId).map(seed => root -> seed.update(LoopKeys.activeAgent, root))
          case Some(current) =>
            if in.history.nonEmpty then Left(ValidationError("history", "history is imported only into a new thread"))
            else Right(current -> Command.empty)
        (agentId, seeded) = start
        nodes <- agents
          .get(agentId)
          .toRight(ValidationError("tool loop", s"the thread's agent '${agentId.value}' is not in this loop"))
      yield
        // the root's boundary hooks guard the whole family; the active agent's own run inside them
        val owners = if agentId == root then Vector(nodes) else Vector(agents(root), nodes)
        threaded(owners, in.query, context, answers)(_.beforeAgent(_, _)) match
          case Left((owner, asked: MiddlewareAsked)) =>
            askedSuspend(owner, asked, MiddlewareHook.BeforeAgent, answers, Some(in))
          case Left((_, error)) => boundary(error, seeded.update, context, GuardrailPhase.Input)
          case Right(transformed) if transformed.trim.isEmpty =>
            // stored, a blank query would fail every model call and every recover after it
            boundary(
              ValidationError("query", "beforeAgent returned a blank query"),
              seeded.update,
              context,
              GuardrailPhase.Input
            )
          case Right(transformed) =>
            NodeResult.Continue(
              seeded
                .update(messages, MessageUpdate.Append(StoredMessage(s"$taskId/user", UserMessage(transformed))))
                .update(LoopKeys.turn, TurnState(0, None, Some(agentId), transfer))
                .goto(nodes.model)
            )
      outcome.fold(NodeResult.Fail(_), identity)

    val input = b.node[AgentInput]("input", writes = inputWrites) { (in, state, context) =>
      inputStep(in, state, context, Vector.empty)
    }

    /** A middleware question's resume node: the asking task's step again, with every answer given so far. */
    def implementAsked[A](owner: AgentNodes, hook: MiddlewareHook, asked: AskedRef[A]): Unit =
      val writes = hook match
        case MiddlewareHook.BeforeAgent   => inputWrites
        case MiddlewareHook.WrapModelCall => modelWrites
        case MiddlewareHook.WrapToolCall  => owner.callWrites
        case MiddlewareHook.AfterAgent    => finishWrites
      b.implement(asked.ref.node, writes = writes) { (resumed, state, context) =>
        val request = resumed.question
        val answers = request.answered :+
          GivenAnswer(owner.agent.id, request.middleware, request.question, asked.answerJson(resumed.answer))
        def missing(what: String) =
          NodeResult.Fail(ValidationError("middleware question", s"the question holds no $what to run again"))
        hook match
          case MiddlewareHook.BeforeAgent => request.input.fold(missing("input"))(inputStep(_, state, context, answers))
          case MiddlewareHook.WrapModelCall => steps(owner.agent.id).model(state, context, answers)
          case MiddlewareHook.WrapToolCall =>
            request.call.fold(missing("tool call")) { task =>
              steps(owner.agent.id).pipeline.reasked(task, request.approved, state, context, answers)
            }
          // the answer is the active agent's: a root middleware asks about a handoff target's answer too
          case MiddlewareHook.AfterAgent =>
            state
              .get(LoopKeys.activeAgent)
              .fold(NodeResult.Fail(_), active => steps(active.getOrElse(root)).finish(state, context, answers))
      }
    agents.values.foreach(owner =>
      owner.askedRefs.foreach { case ((_, hook), asked) => implementAsked(owner, hook, asked) }
    )

    b.compile(input) { state =>
      for
        turn    <- state.get(LoopKeys.turn)
        active  <- state.get(LoopKeys.activeAgent)
        outcome <- turn.outcome.toRight(ValidationError("tool loop", "the run ended without an outcome"))
      yield TurnOutput(outcome, active.getOrElse(root))
    }.map { graph =>
      val all = agents.values.toVector
      def asked(owner: AgentNodes, hook: MiddlewareHook): Set[NodeId] =
        owner.askedRefs.collect { case ((_, h), ref) if h == hook => ref.ref.node.id }.toSet
      // the nodes that continue each agent's model, tool and finish steps after a review
      val continuations = all.flatMap { owner =>
        val agent = owner.agent.id
        val rootsAfterAgent =
          if agent == root then Set.empty[NodeId] else asked(agents(root), MiddlewareHook.AfterAgent)
        Vector(
          (agent, AgentNode.Model) -> asked(owner, MiddlewareHook.WrapModelCall),
          (agent, AgentNode.Tool) -> (asked(owner, MiddlewareHook.WrapToolCall) ++
            owner.askRefs.values.map(_.node.id) + owner.approval.node.id),
          (agent, AgentNode.Finish) -> (asked(owner, MiddlewareHook.AfterAgent) ++ rootsAfterAgent)
        )
      }.toMap
      new ToolLoop(
        graph,
        agents(root).approval,
        all.map(_.approval.node.id).toSet,
        all.flatMap(_.askRefs.values.map(_.node.id)).toSet,
        all.flatMap(_.askedRefs.values.map(_.ref.node.id)).toSet,
        all.map(_.callTool.id).toSet,
        continuations
      )
    }

  /**
   * The appends that seed a new thread with `history`, each message's id derived from the input
   * task. Refused when the thread already has messages, when `history` holds a system message -
   * prompts belong to agents - or when it is not a valid conversation.
   */
  private def imported(history: Vector[Message], stored: Vector[StoredMessage], taskId: String): Result[Command] =
    if history.isEmpty then Right(Command.empty)
    else if stored.nonEmpty then Left(ValidationError("history", "history is imported only into a new thread"))
    else if history.exists { case _: SystemMessage => true; case _ => false } then
      Left(ValidationError("history", "system messages are not imported; prompts belong to agents"))
    else
      Message.validateConversation(history.toList).map { _ =>
        history.zipWithIndex.foldLeft(Command.empty) { case (command, (message, i)) =>
          command.update(Messages.key, MessageUpdate.Append(StoredMessage(s"$taskId/history/$i", message)))
        }
      }

  /** Implements one agent's nodes on the shared builder; returns their steps, for its middleware questions. */
  private def implement(
    b: GraphBuilder,
    nodes: AgentNodes,
    family: Map[AgentId, AgentNodes],
    root: AgentId,
    preserves: Map[(AgentId, AgentId), Boolean]
  ): AgentSteps =
    val messages   = Messages.key
    val agent      = nodes.agent
    val tools      = nodes.tools
    val stack      = nodes.stack
    val pipeline   = Pipeline(agent.id, tools, stack, nodes.approval, nodes.askRefs, nodes.askedRefs)
    val callWrites = nodes.callWrites

    b.implement(nodes.callTool, writes = callWrites) { (task, state, context) =>
      tools.get(task.call.name) match
        case None       => pipeline.error(task, s"Unknown tool '${task.call.name}'", context)
        case Some(tool) => pipeline.admit(tool, task, state, context)
    }

    b.implement(nodes.approval.node, writes = callWrites) { (resumed, state, context) =>
      val request = resumed.question
      val task    = ToolTask(request.assistantMessageId, request.call)
      resumed.answer match
        case ApprovalDecision.Approve        => pipeline.approved(task, request.call, state, context, request.answered)
        case ApprovalDecision.Reject(reason) => pipeline.rejected(task, reason, context)
        case ApprovalDecision.Edit(arguments) =>
          val edit = StateUpdate.update(
            messages,
            MessageUpdate.EditToolCall(request.assistantMessageId, request.call.id, arguments)
          )
          pipeline.approved(task, request.call.copy(arguments = arguments), state, context, request.answered) match
            case NodeResult.Continue(command) =>
              NodeResult.Continue(command.copy(update = edit.combine(command.update)))
            case NodeResult.Suspend(update, q, resume) => NodeResult.Suspend(edit.combine(update), q, resume)
            case failed                                => failed
    }

    nodes.asking.foreach { tool =>
      b.implement(nodes.askRefs(tool.spec.name).node, writes = callWrites) { (resumed, state, context) =>
        pipeline.answered(tool, resumed.question, resumed.answer, state, context)
      }
    }

    val prompt = agent.systemPrompt.map(SystemMessage(_)).toVector
    // this agent's handoff tool names, to their targets' nodes
    val handoffs: Map[String, AgentNodes] =
      agent.handoffs.map(h => HandoffTools.toolName(h.target) -> family(h.target)).toMap

    /**
     * A completed model call, announced durably with `ModelCallCompleted` (counts and usage only, never
     * content), and its usage, for [[LoopKeys.usage]]. AgentTracing builds a run's usage from its
     * `ModelCallCompleted` events the same way, so the two agree.
     */
    def completed(completion: Completion, attempts: Int, context: RunContext): UsageSummary =
      AgentEvents.ModelCallCompleted.emit(
        context,
        events.ModelCallCompleted(
          agent.id.value,
          completion.model,
          attempts,
          completion.message.toolCalls.size,
          completion.usage.map(events.CallUsage.fromTokenUsage),
          completion.estimatedCost
        )
      )
      UsageSummary().add(completion.model, completion.usage.getOrElse(TokenUsage(0, 0, 0)), completion.estimatedCost)

    /** The model's answer stored, its usage recorded, and routed: to finish, to its tool calls, or to a handoff. */
    def stored(turn: TurnState, completion: Completion, attempts: Int, context: RunContext): Command =
      val assistant = completion.message
      // every successful call, whatever it routes to
      val call   = completed(completion, attempts, context)
      val taskId = context.position.taskId.value
      val stored = StoredMessage(s"$taskId/assistant", assistant)
      val appended = Command.empty
        .update(messages, MessageUpdate.Append(stored))
        .update(LoopKeys.usage, call)
        .update(LoopKeys.turn, turn.copy(steps = turn.steps + 1))
      val calls = assistant.toolCalls.toVector
      def toolMessage(call: ToolCall, content: String) =
        MessageUpdate.Append(StoredMessage(s"$taskId/tool/${call.id}", ToolMessage(content, call.id)))
      calls.filter(c => handoffs.contains(c.name)) match
        case none if none.isEmpty =>
          if calls.isEmpty then appended.goto(nodes.finish)
          else appended.fanOut(nodes.batch, nodes.callTool, calls.map(ToolTask(stored.id, _)))
        case Vector(transfer) if calls.size == 1 =>
          val target = handoffs(transfer.name)
          AgentEvents.HandedOff.emit(context, events.HandedOff(agent.id.value, target.agent.id.value))
          appended
            .update(messages, toolMessage(transfer, HandoffTools.transferred(target.agent.id)))
            .update(LoopKeys.activeAgent, target.agent.id)
            .update(LoopKeys.transfer, Some(Transfer(agent.id, target.agent.id, stored.id)))
            .goto(target.model)
        case _ =>
          // a handoff with other calls, or several: nothing runs, every call gets the rule as its error
          val error = ujson.Obj("error" -> HandoffTools.MixedBatch).render()
          calls.filterNot(c => handoffs.contains(c.name)).foreach(pipeline.refusedInBatch(_, error, context))
          calls
            .foldLeft(appended)((command, c) => command.update(messages, toolMessage(c, error)))
            .goto(nodes.model)

    def modelStep(state: ThreadState, context: RunContext, answers: Vector[GivenAnswer]): NodeResult =
      val outcome = state.get(LoopKeys.turn).flatMap { turn =>
        // at the limit the turn ends here: no route, so the run completes without calling the model
        if turn.steps >= agent.maxSteps then
          Right(
            NodeResult.Continue(
              Command.empty.update(LoopKeys.turn, turn.copy(outcome = Some(TurnOutcome.StepLimitReached)))
            )
          )
        else
          for
            history  <- state.get(messages)
            _        <- Message.validateConversation(history.map(_.message).toList)
            transfer <- state.get(LoopKeys.transfer)
            view     <- sent(agent.id, history, transfer, root, preserves)
          yield
            val request  = ModelRequest(prompt ++ view, nodes.offered)
            val attempts = AtomicInteger(0)
            // every completion the model returned in this step, with its attempt number
            val returned = AtomicReference(Vector.empty[(Completion, Int)])
            stack.wrapModelCall(request, answering(agent.id, context, answers)) { req =>
              val result = callModel(agent.model, agent.id, context, attempts)(req)
              result.foreach(c => returned.updateAndGet(_ :+ (c -> attempts.get)))
              result
            } match
              // nothing of the step is stored while the question waits - it runs again once answered - but
              // the model calls a wrapper made before asking were made, and are counted
              case Left(asked: MiddlewareAsked) =>
                val usage = returned.get.map((c, n) => completed(c, n, context))
                val update =
                  if usage.isEmpty then StateUpdate.empty
                  else StateUpdate.update(LoopKeys.usage, usage.reduce(_.merge(_)))
                askedSuspend(nodes, asked, MiddlewareHook.WrapModelCall, answers, update = update)
              case Left(error) => NodeResult.Fail(error)
              // a blank answer without tool calls is refused before it is stored, so the history stays valid
              // and recover asks the model again
              case Right(completion) =>
                NodeResult.fromResult(
                  completion.message.validate.map(_ => stored(turn, completion, attempts.get, context))
                )
      }
      outcome.fold(NodeResult.Fail(_), identity)

    b.implement(nodes.model, writes = modelWrites)((_, state, context) => modelStep(state, context, Vector.empty))

    // this agent's afterAgent stack, then - for a handoff target - the root's, which guards the whole family
    val boundaryOwners = if agent.id == root then Vector(nodes) else Vector(nodes, family(root))

    // the final answer, through every afterAgent; a changed answer replaces the stored message's content.
    // An output Block (an afterAgent `Left`, or a blank answer) ends the run as a finished failure and removes the
    // blocked turn from the history, so no blocked content is stored and the thread continues from before it, with
    // the agent and transfer the turn started with. A middleware question stores nothing, and the answer is
    // guarded again, with the answers, once it is answered.
    def finishStep(state: ThreadState, context: RunContext, answers: Vector[GivenAnswer]): NodeResult =
      val outcome = for
        history <- state.get(messages)
        turn    <- state.get(LoopKeys.turn)
        last <- history.lastOption
          .collect { case StoredMessage(id, a: AssistantMessage) if a.toolCalls.isEmpty => id -> a }
          .toRight(ValidationError("tool loop", "finish found no final assistant message"))
      yield
        val (answerId, assistant) = last
        val removeTurn = history.reverseIterator
          .collectFirst { case StoredMessage(id, _: UserMessage) => id }
          .fold(StateUpdate.empty)(id => StateUpdate.update(messages, MessageUpdate.RemoveTurn(id)))
        val rollback = turn.startAgent.fold(removeTurn)(agentId =>
          removeTurn
            .combine(StateUpdate.update(LoopKeys.activeAgent, agentId))
            .combine(StateUpdate.update(LoopKeys.transfer, turn.startTransfer))
        )
        val completed = Command.empty.update(LoopKeys.turn, turn.copy(outcome = Some(TurnOutcome.Completed)))
        threaded(boundaryOwners, assistant.content, context, answers)(_.afterAgent(_, _)) match
          case Left((owner, asked: MiddlewareAsked)) => askedSuspend(owner, asked, MiddlewareHook.AfterAgent, answers)
          case Left((_, error))                      => boundary(error, rollback, context, GuardrailPhase.Output)
          case Right(changed) if changed.trim.isEmpty =>
            boundary(
              ValidationError("tool loop", "afterAgent returned a blank answer"),
              rollback,
              context,
              GuardrailPhase.Output
            )
          case Right(changed) if changed == assistant.content => NodeResult.Continue(completed)
          case Right(changed) =>
            val replaced = StoredMessage(answerId, assistant.withContent(changed))
            NodeResult.Continue(completed.update(messages, MessageUpdate.Replace(answerId, replaced)))
      outcome.fold(NodeResult.Fail(_), identity)

    b.implement(nodes.finish, writes = finishWrites)((_, state, context) => finishStep(state, context, Vector.empty))

    b.implement(nodes.collect, writes = Set(messages, results)) { (_, state, context) =>
      NodeResult.fromResult(for
        history  <- state.get(messages)
        recorded <- state.get(results)
        source <- history.reverseIterator
          .collectFirst { case StoredMessage(id, a: AssistantMessage) if a.toolCalls.nonEmpty => id -> a }
          .toRight(ValidationError("tool-batch", "no assistant message with tool calls"))
        (sourceId, assistant) = source
        ordered <- assistant.toolCalls.toVector.foldLeft[Result[Vector[ToolResult]]](Right(Vector.empty)) {
          (acc, call) =>
            acc.flatMap { found =>
              recorded.filter(r => r.assistantMessageId == sourceId && r.toolCallId == call.id) match
                case Vector(one) => Right(found :+ one)
                case none if none.isEmpty =>
                  Left(ValidationError("tool-batch", s"tool call '${call.id}' has no result"))
                case _ => Left(ValidationError("tool-batch", s"tool call '${call.id}' has more than one result"))
            }
        }
        _ <- Either.cond(
          recorded.size == ordered.size,
          (),
          ValidationError("tool-batch", "results recorded for calls outside the batch")
        )
      yield
        val toolMessages = ordered.map { r =>
          val content = if r.isError then ujson.Obj("error" -> r.content).render() else r.content
          StoredMessage(s"${context.position.taskId.value}/tool/${r.toolCallId}", ToolMessage(content, r.toolCallId))
        }
        toolMessages
          .foldLeft(Command.empty)((command, m) => command.update(messages, MessageUpdate.Append(m)))
          .remove(results)
          .goto(nodes.model)
      )
    }

    AgentSteps(modelStep, finishStep, pipeline)

  /**
   * What `agent`'s model is sent of `history`, computed at each call and never stored. All of it,
   * unless the recorded [[Transfer]] to `agent` did not preserve context: then the last user message
   * before the transfer's assistant message, followed by that message onwards, so the request starts
   * with the user's question. A non-root agent with no transfer to it recorded, or a transfer whose
   * message or (source, target) pair is unknown, is refused rather than sent the full history.
   */
  private def sent(
    agent: AgentId,
    history: Vector[StoredMessage],
    transfer: Option[Transfer],
    root: AgentId,
    preserves: Map[(AgentId, AgentId), Boolean]
  ): Result[Vector[Message]] =
    val all                  = history.map(_.message)
    def refused(why: String) = Left(ValidationError("handoff", s"agent '${agent.value}' $why"))
    transfer.filter(_.target == agent) match
      case None =>
        if agent == root then Right(all) else refused("is active, but no transfer to it is recorded")
      case Some(t) =>
        preserves.get((t.source, t.target)) match
          case None       => refused(s"has no handoff from '${t.source.value}' in this loop")
          case Some(true) => Right(all)
          case Some(false) =>
            history.indexWhere(_.id == t.messageId) match
              case -1 => refused(s"was transferred to by message '${t.messageId}', which is not in the history")
              case at =>
                val question = all.take(at).reverseIterator.collectFirst { case u: UserMessage => u }
                Right(question.toVector ++ all.drop(at))

  /**
   * A run-boundary failure (a `beforeAgent` or `afterAgent` `Left`): a guardrail's Block, which ends the run as a
   * finished failure and commits `update` first (design 4.13). A cancellation is not a Block: it fails the run
   * as before, leaving the checkpoint `Running` for `recover`. A guardrail's block is reported as a durable
   * `GuardrailBlocked` naming the guardrail and `phase`, never its reason; the Block commits it with the task.
   */
  private def boundary(error: LLMError, update: StateUpdate, context: RunContext, phase: GuardrailPhase): NodeResult =
    error match
      case cancelled: CancelledError => NodeResult.Fail(cancelled)
      case other =>
        other match
          case blocked: GuardrailBlocked =>
            AgentEvents.GuardrailBlocked.emit(context, GuardrailBlock(blocked.guardrail, phase))
          case _ => ()
        NodeResult.Block(update, other)

  /**
   * The model wrappers' innermost function: `model.next`, guarded, since the stack guards only its
   * hooks. Each invocation is an attempt, numbered from 1 by `attempts` (one counter per model task)
   * and announced live with `ModelCallStarted`. It refuses to call the model while the thread is
   * interrupted, returning `Left(CancelledError)`, so a wrapper that retries never calls a cancelled
   * model again. A NonFatal throw is `Left`; a thrown cancellation - a bare `InterruptedException`
   * too - restores the interrupt flag and is `Left(CancelledError)`. Either way it is the model's
   * failure, not a wrapper's.
   */
  private def callModel(model: ModelStep, agent: AgentId, context: RunContext, attempts: AtomicInteger)(
    request: ModelRequest
  ): Result[Completion] =
    if Thread.currentThread().isInterrupted then Left(CancelledError("model"))
    else
      val n = attempts.incrementAndGet()
      AgentEvents.ModelCallStarted.progress(context, events.ModelCallStarted(agent.value, n))
      attempt(model.next(request.messages, request.tools, ModelCall(context, agent, n))) match
        case Right(result) => result
        case Left(thrown) =>
          CancelledError.fromThrowable(thrown, "model") match
            case Some(cancellation) =>
              Thread.currentThread().interrupt()
              Left(cancellation)
            case None => Failure[Completion](thrown).toResult

  /**
   * Runs `body`, returning what it throws as `Left`: a NonFatal exception, or a bare
   * `InterruptedException`, which `Try` alone rethrows (its flag is clear, so the caller restores it).
   */
  private def attempt[A](body: => A): Either[Throwable, A] =
    CancelledError.catchInterrupt(Try(body)) match
      case Right(Success(value))  => Right(value)
      case Right(Failure(thrown)) => Left(thrown)
      case Left(interrupted)      => Left(interrupted)

  /** The call pipeline (design #1278, "The call pipeline"; #1279): one call's checks, chain, tool and outcome. */
  final private class Pipeline(
    agent: AgentId,
    tools: ToolSet,
    stack: MiddlewareStack,
    approval: ResumeRef[ApprovalRequest, ApprovalDecision],
    askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]],
    askedRefs: Map[(MiddlewareId, MiddlewareHook), AskedRef[?]]
  ):

    /** An error result, refused before the middleware chain ran, so of zero duration. */
    def error(task: ToolTask, message: String, context: RunContext): NodeResult =
      record(task, message, isError = true, context, outcomeOfError(message), Duration.Zero)

    /** A reviewer's rejection at approval: the model sees `Rejected: <reason>`. */
    def rejected(task: ToolTask, reason: String, context: RunContext): NodeResult =
      record(task, s"Rejected: $reason", isError = true, context, ToolExecutionOutcome.Rejected, Duration.Zero)

    private def outcomeOfError(message: String): ToolExecutionOutcome =
      if message.startsWith("Denied:") then ToolExecutionOutcome.Denied else ToolExecutionOutcome.Errored

    /** Restores the interrupt flag, so the runtime cancels the task and it records nothing. */
    private def cancelled(error: CancelledError): NodeResult =
      Thread.currentThread().interrupt()
      NodeResult.Fail(error)

    /**
     * Records the call's result, announcing it with [[announce]]. Every result the loop records
     * passes through here, bar a `Success`, which keeps the tool's update (see [[outcome]]); the
     * errors a mixed handoff batch gives its calls are announced by [[refusedInBatch]].
     */
    private def record(
      task: ToolTask,
      content: String,
      isError: Boolean,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): NodeResult =
      announce(task.call, content, isError, context, outcome, duration)
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    /**
     * Sends a call's result live as `ToolCallResult` - with the call's own name and content - and its
     * outcome durably as `ToolExecuted`.
     */
    private def announce(
      call: ToolCall,
      content: String,
      isError: Boolean,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): Unit =
      AgentEvents.ToolCallResult.progress(context, events.ToolCallResult(call.id, content, isError))
      executed(call, context, outcome, duration)

    /**
     * A call of a mixed handoff batch, which runs nothing and gives every call `error` as its result:
     * announced as an `Errored` call of zero duration, as an unknown tool is.
     */
    def refusedInBatch(call: ToolCall, error: String, context: RunContext): Unit =
      announce(call, error, isError = true, context, ToolExecutionOutcome.Errored, Duration.Zero)

    /**
     * The call's outcome, content-free: committed with the task, so stored once per committed call.
     * The tool is named only when the agent has it - a name the model invented is content, and is
     * recorded as [[ToolLoop.UnknownTool]].
     */
    private def executed(
      call: ToolCall,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): Unit =
      val tool = if tools.get(call.name).isDefined then call.name else ToolLoop.UnknownTool
      AgentEvents.ToolExecuted.emit(context, events.ToolExecuted(agent.value, call.id, tool, duration, outcome))

    /** Announces the call live, runs the middleware chain around `innermost`, and times it. */
    private def timed(
      tool: AgentTool[?],
      call: ToolCall,
      toolContext: ToolContext,
      context: RunContext
    )(innermost: () => ToolOutcome): (MiddlewareStack.ToolChainResult, FiniteDuration) =
      AgentEvents.ToolCallStarted.progress(context, events.ToolCallStarted(call.id, tool.spec.name, call.arguments))
      val started = System.nanoTime()
      val chain   = stack.wrapToolCall(ToolCallRequest(tool.spec, call), toolContext)(innermost)
      (chain, (System.nanoTime() - started).nanos)

    private def suspend(
      task: ToolTask,
      call: ToolCall,
      reason: String,
      source: ApprovalSource,
      answers: Vector[GivenAnswer]
    ): NodeResult =
      NodeResult.Suspend(
        StateUpdate.empty,
        ApprovalRequest(task.assistantMessageId, call, reason, source, answers),
        approval
      )

    /** A new call: steps 2-4, then the middleware chain around the tool. */
    def admit[A](tool: AgentTool[A], task: ToolTask, state: ThreadState, context: RunContext): NodeResult =
      checked(tool, task, task.call, context) match
        case Left(refused) => refused
        case Right(args)   => execute(tool, task, task.call, args, state, context)

    /**
     * An approved call, as approved or with edited arguments: checked again, then run through the
     * whole chain with `approved = true`, so every wrapper - a deny rule too - sees it again, with the
     * answers its middleware questions were given before it asked.
     */
    def approved(
      task: ToolTask,
      call: ToolCall,
      state: ThreadState,
      context: RunContext,
      answers: Vector[GivenAnswer]
    ): NodeResult =
      rerun(task, call, approved = true, state, context, answers)

    /**
     * A call whose middleware question was answered: checked again, then run through the whole chain
     * with the approval it asked with and every answer given so far.
     */
    def reasked(
      task: ToolTask,
      approved: Boolean,
      state: ThreadState,
      context: RunContext,
      answers: Vector[GivenAnswer]
    ): NodeResult =
      rerun(task, task.call, approved, state, context, answers)

    private def rerun(
      task: ToolTask,
      call: ToolCall,
      approved: Boolean,
      state: ThreadState,
      context: RunContext,
      answers: Vector[GivenAnswer]
    ): NodeResult =
      tools.get(call.name) match
        case None => error(task, s"Unknown tool '${call.name}'", context)
        case Some(tool) =>
          checked(tool, task, call, context) match
            case Left(refused) => refused
            case Right(args)   => execute(tool, task, call, args, state, context, approved, answers)

    /** An answered question: decodes the stored call, question and answer, and resumes the tool. */
    def answered[A](
      tool: AgentTool[A],
      request: ToolQuestionRequest,
      answer: ujson.Value,
      state: ThreadState,
      context: RunContext
    ): NodeResult =
      val task = ToolTask(request.assistantMessageId, request.call)
      val name = tool.spec.name
      tool.spec.question match
        case Some(declared: ToolQuestion[q, ans]) =>
          val decoded = for
            args <- decode(tool, request.call).left.map(m => s"Invalid arguments for '$name': $m")
            question <- read(request.question)(using declared.questionCodec).left
              .map(m => s"Invalid question for '$name': $m")
            reply <- read(answer)(using declared.answerCodec).left.map(m => s"Invalid answer for '$name': $m")
          yield (args, question, reply)
          decoded match
            case Left(message) => error(task, message, context)
            case Right((args, question, reply)) =>
              val toolContext = ToolContext(
                answering(agent, context, request.answered),
                ToolCallId(request.call.id),
                state,
                request.approved
              )
              val (chain, took) = timed(tool, request.call, toolContext, context)(
                innermost(name)(AgentTool.resumeWith(tool, args, question, reply, toolContext))
              )
              outcome(
                tool,
                task,
                request.call,
                request.approved,
                chain,
                context,
                took,
                request.answered,
                resumed = true
              )
        case _ => error(task, s"Tool '$name' does not take answers", context)

    /**
     * Steps 2-4: validate the raw arguments, decode them, run the tool's own check. `Left` is the
     * task's result instead: an error result, or a cancellation.
     */
    private def checked[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      context: RunContext
    ): Either[NodeResult, A] =
      val name                                 = tool.spec.name
      def refused(message: String): NodeResult = error(task, s"Invalid arguments for '$name': $message", context)
      // the validator and the check are caller code: a throw refuses the call rather than failing the
      // run, unless it is a cancellation, which cancels the task as a throwing tool's does
      def guarded[T](run: => T): Either[NodeResult, T] =
        Try(run).toEither.left.map { thrown =>
          CancelledError.fromThrowable(thrown, s"tool $name").fold(refused(describe(thrown)))(cancelled)
        }
      for
        violations <- guarded(tools.validator.validate(tool.spec.argumentSchema, argumentsOf(tool, call)))
        _          <- Either.cond(violations.isEmpty, (), refused(violations.mkString("; ")))
        args       <- decode(tool, call).left.map(refused)
        check      <- guarded(tool.spec.validateDecoded(args))
        _          <- check.left.map(e => refused(e.message))
      yield args

    private def decode[A](tool: AgentTool[A], call: ToolCall): Either[String, A] =
      read(argumentsOf(tool, call))(using tool.spec.codec)

    /**
     * The call's arguments as the tool sees them. As core's `ToolFunction.execute` does, `null` for a
     * tool that requires nothing is the empty object; for a tool with required fields it stays `null`
     * and fails validation.
     */
    private def argumentsOf(tool: AgentTool[?], call: ToolCall): ujson.Value =
      call.arguments match
        case ujson.Null =>
          tool.spec.argumentSchema match
            case schema: ujson.Obj
                if schema.value.get("type").contains(ujson.Str("object")) &&
                  schema.value.get("required").forall { case ujson.Arr(names) => names.isEmpty; case _ => false } =>
              ujson.Obj()
            case _ => ujson.Null
        case other => other

    /** Decodes `json`, describing a failure by the JSON path it happened at and its cause. */
    private def read[T: ReadWriter](json: ujson.Value): Either[String, T] =
      Try(upickle.default.read[T](json)).toEither.left.map {
        case traced: upickle.core.TraceVisitor.TraceException =>
          s"${traced.jsonPath}: ${Option(traced.getCause).fold("does not decode")(describe)}"
        case other => describe(other)
      }

    private def describe(thrown: Throwable): String = Option(thrown.getMessage).getOrElse(thrown.toString)

    private def execute[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      args: A,
      state: ThreadState,
      context: RunContext,
      approved: Boolean = false,
      answers: Vector[GivenAnswer] = Vector.empty
    ): NodeResult =
      val toolContext = ToolContext(answering(agent, context, answers), ToolCallId(call.id), state, approved)
      val (chain, took) =
        timed(tool, call, toolContext, context)(innermost(tool.spec.name)(tool.execute(args, toolContext)))
      outcome(tool, task, call, approved, chain, context, took, answers)

    /**
     * The chain's innermost function, for one chain invocation: the tool's `execute` or `resume`,
     * [[guarded]]. It refuses to start the tool while the thread is interrupted, returning
     * `Fatal(CancelledError)` - so a tool that reported its cancellation as an `Error` is not run
     * again by a wrapper that retries. Once it has produced `Fatal(CancelledError)`, every later
     * call - a wrapper retrying - returns that same outcome without running the tool again.
     */
    private def innermost(name: String)(run: => ToolOutcome): () => ToolOutcome =
      val cancelledWith = new AtomicReference[Option[ToolOutcome]](None)
      () =>
        cancelledWith.get.getOrElse {
          val result =
            if Thread.currentThread().isInterrupted then ToolOutcome.Fatal(CancelledError(s"tool $name"))
            else guarded(name)(run)
          result match
            case ToolOutcome.Fatal(_: CancelledError) => cancelledWith.set(Some(result))
            case _                                    => ()
          result
        }

    /**
     * Guards the tool so that wrappers see a throwing tool as an outcome. A thrown NonFatal exception
     * is an error result; a thrown cancellation (a bare `InterruptedException`, an interrupt wrapped
     * in another exception, or one thrown with the flag set) restores the interrupt flag at once and
     * is `Fatal(CancelledError)`.
     */
    private def guarded(name: String)(run: => ToolOutcome): ToolOutcome =
      attempt(run) match
        case Right(result) => result
        case Left(thrown) =>
          CancelledError.fromThrowable(thrown, s"tool $name") match
            case Some(cancellation) =>
              Thread.currentThread().interrupt()
              ToolOutcome.Fatal(cancellation)
            case None => ToolOutcome.Error(s"Tool '$name' failed: ${describe(thrown)}")

    /**
     * Step 6: maps what the chain returned. `Fatal(CancelledError)` restores the interrupt flag, so
     * the runtime cancels the task and it records nothing; a wrapper's `MiddlewareFailed` fails the
     * run as itself, any other `Fatal` as `ToolFailed`. A `NeedsApproval` or an `Ask` is attributed to
     * the tool or to the wrapper that raised it, and carries `answers` - the middleware questions' answers
     * so far - so the chain runs again with them. `resumed` is true when the tool is continuing after a question.
     */
    private def outcome[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      approved: Boolean,
      chain: MiddlewareStack.ToolChainResult,
      context: RunContext,
      took: FiniteDuration,
      answers: Vector[GivenAnswer],
      resumed: Boolean = false
    ): NodeResult =
      val name = tool.spec.name
      def failRun(error: LLMError): NodeResult =
        NodeResult.Fail(GraphError.ToolFailed(ToolName(name), ToolCallId(call.id), error))
      chain.outcome match
        case ToolOutcome.Success(content, update) =>
          val allowed = tool.writes ++ stack.writes
          val undeclared =
            update.operations.map(_.key.id).filterNot(k => allowed.exists(_.id == k)).map(_.value).distinct
          if undeclared.isEmpty then
            val result = rendered(content)
            AgentEvents.ToolCallResult.progress(context, events.ToolCallResult(call.id, result, isError = false))
            executed(task.call, context, ToolExecutionOutcome.Succeeded, took)
            NodeResult.Continue(
              Command(update, Nil)
                .update(results, ToolResult(task.assistantMessageId, call.id, result, isError = false))
            )
          else
            failRun(
              ValidationError(
                "tool update",
                s"tool '$name' updated ${undeclared.map(k => s"'$k'").mkString(", ")}, which neither it nor any middleware declares"
              )
            )
        case ToolOutcome.Error(message) =>
          record(task, message, isError = true, context, outcomeOfError(message), took)
        case ToolOutcome.NeedsApproval(reason) =>
          val (asker, source) = chain.raisedBy match
            case None     => (s"Tool '$name'", ApprovalSource.Tool)
            case Some(id) => (s"Middleware '${id.value}'", ApprovalSource.Middleware(id))
          // approving would run `execute` again and lose the answer
          def errored(message: String) =
            record(task, message, isError = true, context, ToolExecutionOutcome.Errored, took)
          if resumed then errored(s"$asker asked for approval after a question: $reason")
          else if approved then errored(s"$asker asked for approval again: $reason")
          else
            // a suspending task commits its events with its pending write
            executed(task.call, context, ToolExecutionOutcome.NeedsApproval, took)
            suspend(task, call, reason, source, answers)
        case ToolOutcome.Ask(question) if chain.raisedBy.isDefined =>
          middlewareAsked(task, call, approved, chain.raisedBy.get, question, context, took, answers, resumed)(failRun)
        case ToolOutcome.Ask(question) =>
          (tool.spec.question, askRefs.get(name)) match
            case (Some(declared: ToolQuestion[q, ?]), Some(ref)) =>
              // a question of another type than the declared one fails to encode: a tool bug, like an undeclared one
              Try(upickle.default.writeJs(question.asInstanceOf[q])(using declared.questionCodec)).toResult match
                case Right(json) =>
                  executed(task.call, context, ToolExecutionOutcome.Asked, took)
                  NodeResult.Suspend(
                    StateUpdate.empty,
                    ToolQuestionRequest(task.assistantMessageId, call, json, approved, answers),
                    ref
                  )
                case Left(e) =>
                  failRun(
                    ValidationError("tool question", s"tool '$name' asked a question of another type: ${e.message}")
                  )
            case _ => failRun(ValidationError("tool question", s"tool '$name' asked a question it does not declare"))
        case ToolOutcome.Fatal(cancellation: CancelledError)        => cancelled(cancellation)
        case ToolOutcome.Fatal(failed: GraphError.MiddlewareFailed) => NodeResult.Fail(failed)
        case ToolOutcome.Fatal(error)                               => failRun(error)

    /**
     * A wrapper's question about the call: suspends at the asking middleware's tool-call resume node with
     * the call, as approved or edited, so the chain runs again once it is answered. A question the
     * middleware does not declare, or of another type, fails the run as a tool's does; one while the tool
     * continues after its own question is the call's error result, because running the chain again would
     * lose the tool's answer.
     */
    private def middlewareAsked(
      task: ToolTask,
      call: ToolCall,
      approved: Boolean,
      asker: MiddlewareId,
      question: Any,
      context: RunContext,
      took: FiniteDuration,
      answers: Vector[GivenAnswer],
      resumed: Boolean
    )(failRun: LLMError => NodeResult): NodeResult =
      val declared = stack.ordered.find(_.id == asker).flatMap(_.declaredQuestion)
      (declared, askedRefs.get((asker, MiddlewareHook.WrapToolCall))) match
        case (Some(codecs: ToolQuestion[q, ?]), Some(ref)) =>
          if resumed then
            record(
              task,
              s"Middleware '${asker.value}' asked a question after a tool question",
              isError = true,
              context,
              ToolExecutionOutcome.Errored,
              took
            )
          else
            Try(upickle.default.writeJs(question.asInstanceOf[q])(using codecs.questionCodec)).toResult match
              case Right(json) =>
                // a suspending task commits its events with its pending write
                executed(task.call, context, ToolExecutionOutcome.Asked, took)
                ref.suspend(
                  MiddlewareQuestionRequest(
                    agent,
                    asker,
                    MiddlewareHook.WrapToolCall,
                    json,
                    None,
                    Some(ToolTask(task.assistantMessageId, call)),
                    approved,
                    answers
                  )
                )
              case Left(e) =>
                failRun(
                  ValidationError(
                    "middleware question",
                    s"middleware '${asker.value}' asked a question of another type: ${e.message}"
                  )
                )
        case _ =>
          failRun(
            ValidationError("middleware question", s"middleware '${asker.value}' asked a question it does not declare")
          )

    /**
     * A string result is the string itself, not a quoted JSON string - except a blank one, which a
     * `ToolMessage` refuses, so it is recorded in its quoted JSON form.
     */
    private def rendered(content: ujson.Value): String = content match
      case ujson.Str(s) if s.trim.nonEmpty => s
      case other                           => other.render()
