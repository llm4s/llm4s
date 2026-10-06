package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.events
import org.llm4s.agent.events.{ AgentEvents, GuardrailBlock, GuardrailPhase, ToolExecutionOutcome }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{
  GuardrailBlocked,
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

/** The question an approval interrupt asks: one tool call, from one assistant message. */
final case class ApprovalRequest(assistantMessageId: String, call: ToolCall, reason: String, source: ApprovalSource)
    derives ReadWriter

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
final case class ToolQuestionRequest(
  assistantMessageId: String,
  call: ToolCall,
  question: ujson.Value,
  approved: Boolean = false
) derives ReadWriter

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
  askNodes: Set[NodeId]
):

  /** The approval requests a suspended run is waiting on, at any agent's approval node. */
  def requests(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ApprovalRequest)]] =
    pending[ApprovalRequest](suspended, approvalNodes.contains)

  /** The tool questions a suspended run is waiting on; read each one's question with [[ToolLoop.question]]. */
  def questions(suspended: RunResult.Suspended): Result[Vector[(InterruptId, ToolQuestionRequest)]] =
    pending[ToolQuestionRequest](suspended, askNodes.contains)

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
      .filter(i => at(i.resumeNode))
      .foldLeft[Result[Vector[(InterruptId, Q)]]](Right(Vector.empty)) { (acc, i) =>
        acc.flatMap(found => Try(upickle.default.read[Q](i.question)).toResult.map(r => found :+ (i.id -> r)))
      }

object ToolLoop:

  /** A pending question, decoded as the asking tool's question type `Q`. */
  def question[Q: ReadWriter](request: ToolQuestionRequest): Result[Q] =
    Try(upickle.default.read[Q](request.question)).toResult

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
    val askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]]
  ):
    def agent: LoopAgent             = prepared.agent
    def tools: ToolSet               = prepared.tools
    def offered: ToolSet             = prepared.offered
    def stack: MiddlewareStack       = prepared.stack
    def asking: Vector[AgentTool[?]] = tools.tools.filter(_.spec.question.isDefined)

  private def declare(b: GraphBuilder, prepared: Prepared): AgentNodes =
    val prefix  = s"${prepared.agent.id.value}/"
    val collect = b.declare[Unit](s"${prefix}collect")
    val askRefs = prepared.tools.tools
      .filter(_.spec.question.isDefined)
      .map(t => t.spec.name -> b.declareResume[ToolQuestionRequest, ujson.Value](s"${prefix}ask/${t.spec.name}"))
      .toMap
    AgentNodes(
      prepared,
      model = b.declare[Unit](s"${prefix}model"),
      finish = b.declare[Unit](s"${prefix}finish"),
      collect = collect,
      callTool = b.declare[ToolTask](s"${prefix}call-tool"),
      approval = b.declareResume[ApprovalRequest, ApprovalDecision](s"${prefix}approval"),
      batch = b.dynamicJoin(s"${prefix}tool-batch", collect),
      askRefs = askRefs
    )

  private def assemble(id: String, version: String, root: AgentId, prepared: Vector[Prepared]): Result[ToolLoop] =
    val messages = Messages.key
    val b        = GraphBuilder(id, version)
    val agents   = prepared.map(p => p.agent.id -> declare(b, p)).toMap
    // whether a transfer keeps the context, by (source, target)
    val preserves = prepared.flatMap(p => p.agent.handoffs.map(h => (p.agent.id, h.target) -> h.preserveContext)).toMap
    prepared.foreach(p => implement(b, agents(p.agent.id), agents, root, preserves))

    // An input Block (a beforeAgent `Left`, or a blank query) stores nothing of the turn: the run ends as a
    // finished failure, committing only a new thread's seed - its imported history and the root as active agent.
    val input = b.node[AgentInput]("input", writes = Set(messages, LoopKeys.activeAgent, LoopKeys.turn)) {
      (in, state, context) =>
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
          val stacks = if agentId == root then Vector(nodes.stack) else Vector(agents(root).stack, nodes.stack)
          stacks.foldLeft[Result[String]](Right(in.query))((acc, s) => acc.flatMap(s.beforeAgent(_, context))) match
            case Left(error) => boundary(error, seeded.update, context, GuardrailPhase.Input)
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
    }

    b.compile(input) { state =>
      for
        turn    <- state.get(LoopKeys.turn)
        active  <- state.get(LoopKeys.activeAgent)
        outcome <- turn.outcome.toRight(ValidationError("tool loop", "the run ended without an outcome"))
      yield TurnOutput(outcome, active.getOrElse(root))
    }.map { graph =>
      val all = agents.values.toVector
      new ToolLoop(
        graph,
        agents(root).approval,
        all.map(_.approval.node.id).toSet,
        all.flatMap(_.askRefs.values.map(_.node.id)).toSet
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

  /** Implements one agent's nodes on the shared builder. */
  private def implement(
    b: GraphBuilder,
    nodes: AgentNodes,
    family: Map[AgentId, AgentNodes],
    root: AgentId,
    preserves: Map[(AgentId, AgentId), Boolean]
  ): Unit =
    val messages = Messages.key
    val agent    = nodes.agent
    val tools    = nodes.tools
    val stack    = nodes.stack
    val pipeline = Pipeline(agent.id, tools, stack, nodes.approval, nodes.askRefs)
    // the call-tool, approval and ask nodes may write any tool's keys; each call is checked against its own tool's
    val callWrites: Set[StateKey[?, ?]] = tools.tools.flatMap(_.writes).toSet ++ stack.writes ++ Set(results, messages)

    b.implement(nodes.callTool, writes = callWrites) { (task, state, context) =>
      tools.get(task.call.name) match
        case None       => pipeline.error(task, s"Unknown tool '${task.call.name}'", context)
        case Some(tool) => pipeline.admit(tool, task, state, context)
    }

    b.implement(nodes.approval.node, writes = callWrites) { (resumed, state, context) =>
      val request = resumed.question
      val task    = ToolTask(request.assistantMessageId, request.call)
      resumed.answer match
        case ApprovalDecision.Approve        => pipeline.approved(task, request.call, state, context)
        case ApprovalDecision.Reject(reason) => pipeline.rejected(task, reason, context)
        case ApprovalDecision.Edit(arguments) =>
          val edit = StateUpdate.update(
            messages,
            MessageUpdate.EditToolCall(request.assistantMessageId, request.call.id, arguments)
          )
          pipeline.approved(task, request.call.copy(arguments = arguments), state, context) match
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

    val modelWrites: Set[StateKey[?, ?]] =
      Set(messages, LoopKeys.usage, LoopKeys.turn, LoopKeys.activeAgent, LoopKeys.transfer)
    b.implement(nodes.model, writes = modelWrites) { (_, state, context) =>
      NodeResult.fromResult(state.get(LoopKeys.turn).flatMap { turn =>
        // at the limit the turn ends here: no route, so the run completes without calling the model
        if turn.steps >= agent.maxSteps then
          Right(Command.empty.update(LoopKeys.turn, turn.copy(outcome = Some(TurnOutcome.StepLimitReached))))
        else
          for
            history  <- state.get(messages)
            _        <- Message.validateConversation(history.map(_.message).toList)
            transfer <- state.get(LoopKeys.transfer)
            view     <- sent(agent.id, history, transfer, root, preserves)
            request  = ModelRequest(prompt ++ view, nodes.offered)
            attempts = AtomicInteger(0)
            completion <- stack.wrapModelCall(request, context)(callModel(agent.model, agent.id, context, attempts))
            assistant = completion.message
            // a blank answer without tool calls is refused before it is stored, so the history stays valid
            // and recover asks the model again
            _ <- assistant.validate
          yield
            // every successful call, whatever it routes to; counts and usage only, never content
            AgentEvents.ModelCallCompleted.emit(
              context,
              events.ModelCallCompleted(
                agent.id.value,
                completion.model,
                attempts.get,
                assistant.toolCalls.size,
                completion.usage.map(events.CallUsage.fromTokenUsage),
                completion.estimatedCost
              )
            )
            val taskId = context.position.taskId.value
            val stored = StoredMessage(s"$taskId/assistant", assistant)
            val call = UsageSummary()
              .add(completion.model, completion.usage.getOrElse(TokenUsage(0, 0, 0)), completion.estimatedCost)
            // AgentTracing builds a run's usage from its ModelCallCompleted events the same way
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
                calls
                  .foldLeft(appended)((command, c) => command.update(messages, toolMessage(c, error)))
                  .goto(nodes.model)
      })
    }

    // this agent's afterAgent stack, then - for a handoff target - the root's, which guards the whole family
    val boundaryStacks =
      if agent.id == root then Vector(stack) else Vector(stack, family(root).stack)

    // the final answer, through every afterAgent; a changed answer replaces the stored message's content.
    // An output Block (an afterAgent `Left`, or a blank answer) ends the run as a finished failure and removes the
    // blocked turn from the history, so no blocked content is stored and the thread continues from before it, with
    // the agent and transfer the turn started with.
    b.implement(nodes.finish, writes = Set(messages, LoopKeys.turn, LoopKeys.activeAgent, LoopKeys.transfer)) {
      (_, state, context) =>
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
          boundaryStacks.foldLeft[Result[String]](Right(assistant.content))((acc, s) =>
            acc.flatMap(s.afterAgent(_, context))
          ) match
            case Left(error) => boundary(error, rollback, context, GuardrailPhase.Output)
            case Right(changed) if changed.trim.isEmpty =>
              boundary(
                ValidationError("tool loop", "afterAgent returned a blank answer"),
                rollback,
                context,
                GuardrailPhase.Output
              )
            case Right(changed) if changed == assistant.content => NodeResult.Continue(completed)
            case Right(changed) =>
              val replaced = StoredMessage(answerId, assistant.copy(contentOpt = Some(changed)))
              NodeResult.Continue(completed.update(messages, MessageUpdate.Replace(answerId, replaced)))
        outcome.fold(NodeResult.Fail(_), identity)
    }

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
    askRefs: Map[String, ResumeRef[ToolQuestionRequest, ujson.Value]]
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
     * Records the call's result, sending it live as `ToolCallResult` and its outcome durably as
     * `ToolExecuted`. Every result the loop records passes through here, bar a `Success`, which keeps
     * the tool's update (see [[outcome]]).
     */
    private def record(
      task: ToolTask,
      content: String,
      isError: Boolean,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): NodeResult =
      AgentEvents.ToolCallResult.progress(context, events.ToolCallResult(task.call.id, content, isError))
      executed(task, context, outcome, duration)
      NodeResult.Continue(
        Command.empty.update(results, ToolResult(task.assistantMessageId, task.call.id, content, isError))
      )

    /** The call's outcome, content-free: committed with the task, so stored once per committed call. */
    private def executed(
      task: ToolTask,
      context: RunContext,
      outcome: ToolExecutionOutcome,
      duration: FiniteDuration
    ): Unit =
      AgentEvents.ToolExecuted.emit(
        context,
        events.ToolExecuted(agent.value, task.call.id, task.call.name, duration, outcome)
      )

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

    private def suspend(task: ToolTask, call: ToolCall, reason: String, source: ApprovalSource): NodeResult =
      NodeResult.Suspend(StateUpdate.empty, ApprovalRequest(task.assistantMessageId, call, reason, source), approval)

    /** A new call: steps 2-4, then the middleware chain around the tool. */
    def admit[A](tool: AgentTool[A], task: ToolTask, state: ThreadState, context: RunContext): NodeResult =
      checked(tool, task, task.call, context) match
        case Left(refused) => refused
        case Right(args)   => execute(tool, task, task.call, args, state, context)

    /**
     * An approved call, as approved or with edited arguments: checked again, then run through the
     * whole chain with `approved = true`, so every wrapper - a deny rule too - sees it again.
     */
    def approved(task: ToolTask, call: ToolCall, state: ThreadState, context: RunContext): NodeResult =
      tools.get(call.name) match
        case None => error(task, s"Unknown tool '${call.name}'", context)
        case Some(tool) =>
          checked(tool, task, call, context) match
            case Left(refused) => refused
            case Right(args)   => execute(tool, task, call, args, state, context, approved = true)

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
              val toolContext = ToolContext(context, ToolCallId(request.call.id), state, request.approved)
              val (chain, took) = timed(tool, request.call, toolContext, context)(
                innermost(name)(AgentTool.resumeWith(tool, args, question, reply, toolContext))
              )
              outcome(tool, task, request.call, request.approved, chain, context, took, resumed = true)
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
      approved: Boolean = false
    ): NodeResult =
      val toolContext = ToolContext(context, ToolCallId(call.id), state, approved)
      val (chain, took) =
        timed(tool, call, toolContext, context)(innermost(tool.spec.name)(tool.execute(args, toolContext)))
      outcome(tool, task, call, approved, chain, context, took)

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
     * run as itself, any other `Fatal` as `ToolFailed`. A `NeedsApproval` is attributed to the tool
     * or to the wrapper that raised it. `resumed` is true when the tool is continuing after a question.
     */
    private def outcome[A](
      tool: AgentTool[A],
      task: ToolTask,
      call: ToolCall,
      approved: Boolean,
      chain: MiddlewareStack.ToolChainResult,
      context: RunContext,
      took: FiniteDuration,
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
            executed(task, context, ToolExecutionOutcome.Succeeded, took)
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
            executed(task, context, ToolExecutionOutcome.NeedsApproval, took)
            suspend(task, call, reason, source)
        case ToolOutcome.Ask(question) =>
          (tool.spec.question, askRefs.get(name)) match
            case (Some(declared: ToolQuestion[q, ?]), Some(ref)) =>
              // a question of another type than the declared one fails to encode: a tool bug, like an undeclared one
              Try(upickle.default.writeJs(question.asInstanceOf[q])(using declared.questionCodec)).toResult match
                case Right(json) =>
                  executed(task, context, ToolExecutionOutcome.Asked, took)
                  NodeResult.Suspend(
                    StateUpdate.empty,
                    ToolQuestionRequest(task.assistantMessageId, call, json, approved),
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
     * A string result is the string itself, not a quoted JSON string - except a blank one, which a
     * `ToolMessage` refuses, so it is recorded in its quoted JSON form.
     */
    private def rendered(content: ujson.Value): String = content match
      case ujson.Str(s) if s.trim.nonEmpty => s
      case other                           => other.render()
