package org.llm4s.agent

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.agent.delegation.{ ChildRef, DelegatedAnswers, DelegatedRequest, DelegationPlan }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.agent.graph.toolloop.{ AgentInput, LoopKeys, SettledTurn, ToolLoop, TurnOutput }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Message, SystemMessage }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * An agent family compiled onto the graph runtime: a model/tool loop whose conversations are
 * threads of a [[org.llm4s.agent.graph.GraphRuntime]]. Built with [[Agent.builder]].
 *
 * {{{
 * for
 *   agent  <- Agent.builder("assistant", client).withTools(registry).build()
 *   first  <- agent.run("What is the weather in Paris?")
 *   second <- agent.continueConversation(first, "And in London?")
 * yield second.answer
 * }}}
 *
 * Before a turn is stored its query is admitted (#2854): the family's `admit` stages - input guardrails among them -
 * may rewrite it, and only the rewrite is stored, or refuse it, and then the turn ends `Blocked` with nothing of the
 * query in the checkpoint store, the event log or a trace.
 *
 * Every run is a turn on a thread. A run that reaches the graph's end is `Right` with status
 * `Completed`, `Truncated` (the answer was cut off at the model's output limit), `StepLimitReached` or
 * `TokenLimitReached`; one a guardrail blocks is `Right` with
 * `Blocked`, the blocked turn absent from the thread; one that parks on approvals, questions or breakpoints is `Right` with
 * `Suspended`, and continues with [[resume]]. Anything else is `Left`: a blank query and the
 * runtime's refusals (`ThreadBusy`, `TenantMismatch`, `IncompleteRun`, `PendingInterrupts`, ...)
 * leave the thread unchanged, as does another middleware's `beforeAgent` or `afterAgent` failure
 * (a Block without a guardrail; a blank query or answer from a hook is one), which also ends the
 * run; a failed run - a provider error, a tool's `Fatal`, a model or tool wrapper's failure,
 * cancellation, a deadline - leaves the thread for [[recover]]. So does a run that lost its claim on the
 * thread (`GraphError.ClaimLost`), once the thread is free: `recover` is refused with `ThreadBusy` while
 * another runtime sharing the store holds it - retry - and if a successor finished the work, the
 * thread's state shows it (`recover` then reports `NothingToRecover`). If the lease merely lapsed, with
 * no successor, `recover` continues the interrupted turn.
 */
@Stable
final class Agent private[agent] (
  val id: AgentId,
  toolLoop: ToolLoop,
  private[agent] val runtime: GraphRuntime,
  tracing: Option[Tracing],
  interruptBefore: Set[NodeId] = Set.empty,
  interruptAfter: Set[NodeId] = Set.empty,
  retention: Option[RetentionPolicy] = None,
  childRetention: Option[Int] = None,
  delegationPlan: DelegationPlan = DelegationPlan.empty
):

  /**
   * What this agent's subagents run with (design 4.45): for each, its tools (own or granted), middleware, ancestors'
   * permission rules, model, budget and whether it may delegate. Empty for an agent without subagents.
   */
  @Experimental
  def delegation: DelegationPlan = delegationPlan

  /**
   * The compiled model/tool loop: its graph (`loop.graph`, to restore a checkpoint's state or read the graph's
   * version) and the nodes each agent of the family runs. Internals of the runtime, so `@Experimental`.
   */
  @Experimental
  def loop: ToolLoop = toolLoop

  /**
   * `config` with the agent's static breakpoints ([[AgentBuilder.withInterruptBefore]],
   * [[AgentBuilder.withInterruptAfter]]) added to its own.
   */
  private[agent] def configured(config: RunConfig): RunConfig =
    config
      .withInterruptBefore(config.interruptBefore ++ interruptBefore)
      .withInterruptAfter(config.interruptAfter ++ interruptAfter)
      // a harness's retention, applied as each turn is admitted (design 4.37), unless the run sets its own;
      // keepAll removes nothing, so it is not applied at all
      .withRetention(config.retention.orElse(retention).filter(_ != RetentionPolicy.keepAll))
      // a delegating agent's DelegationConfig.childRetention and the run's own: both bound it, the tighter wins (4.44)
      .withChildRetention(
        childRetention.fold(config.childRetention)(math.min(_, config.childRetention))
      )

  /**
   * One turn on a new thread with a random id. The thread stays in the agent's runtime - on the
   * default in-memory runtime, in memory - until [[forget]] removes it; a caller that does not
   * continue the conversation should forget `result.threadId` when done.
   *
   * A `Left` carries no thread id, so nothing could recover or forget the thread: a turn that fails
   * - cancelled by an interrupt included - is forgotten here once it has ended. A cancelled turn
   * whose provider ignores its interrupt past [[AgentRun.Drain]] has not ended, and its thread is
   * left in the runtime (logged at WARN). For a turn to recover, name its thread: `run(threadId, query)`.
   */
  def run(query: String, config: RunConfig = RunConfig()): Result[AgentResult] =
    val threadId = ThreadId(java.util.UUID.randomUUID().toString)
    forgottenOnFailure(threadId, config)(run(threadId, query, config, Nil))

  /**
   * One turn on `threadId`: a new thread is created, seeded with `history`; on a completed or
   * blocked thread it is the next turn, and `history` must be empty. `history` holds no system
   * message and must be a valid conversation. A blank `query`, a `history` refused for its content,
   * or one given for a thread that exists, is a `ValidationError`; the runtime's refusals -
   * `GraphError.TenantMismatch` for another tenant's thread, `ThreadBusy`, `IncompleteRun`,
   * `PendingInterrupts` - come back unchanged. Either way no thread is created and an existing one
   * is unchanged.
   *
   * `history` is admitted with the query (#3235): every imported user message's text goes through the agent's input
   * guardrails and `admit` stages as the query does, and is stored and sent as they rewrite it. Assistant messages
   * and tool results are imported as they are. When a stage refuses the query or an imported message, the turn ends
   * `AgentStatus.Blocked(guardrail, reason)` as any refused query does, but nothing of it is stored: no thread is
   * created, and the import can be made again on the same `threadId`.
   *
   * Interrupting the calling thread cancels the turn: `run` returns `Left(CancelledError)` with the
   * interrupt flag still set, once the turn has ended, leaving the thread for [[recover]]. It waits up to
   * 5 seconds ([[AgentRun.Drain]]) for the turn to end; with tracing, delivering the ended turn's last
   * trace events can add to that. A turn whose provider ignores its interrupt for longer is logged at
   * WARN and left to end on its own, and until it does the thread is `GraphError.ThreadBusy`. With
   * tracing, the cancelled turn's trace is complete when `run` returns. A turn that had already begun
   * committing its outcome when the interrupt came cannot be cancelled: `run` returns that outcome -
   * `Completed`, `Suspended`, ... - with the interrupt flag still set. A caller already interrupted
   * gets `Left(CancelledError)` without a turn being started. To keep a turn running past an
   * interrupt, use [[start]] and await the [[AgentRun]].
   */
  def run(threadId: ThreadId, query: String, config: RunConfig, history: Seq[Message]): Result[AgentResult] =
    blocking(start(threadId, query, config, history))

  /** One turn on `threadId`, new, completed or blocked. */
  def run(threadId: ThreadId, query: String, config: RunConfig): Result[AgentResult] =
    run(threadId, query, config, Nil)

  /** One turn on `threadId`, new, completed or blocked, with the default config. */
  def run(threadId: ThreadId, query: String): Result[AgentResult] = run(threadId, query, RunConfig(), Nil)

  /** The next turn on `previous`'s thread: only its `threadId` is read. */
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig()): Result[AgentResult] =
    run(previous.threadId, query, config, Nil)

  /**
   * `first` on a new thread, then each of `followUps` on it, stopping at the first turn whose
   * status is not `Completed`; returns the last turn's result. Each turn is its own run: the first
   * runs with `config`, each follow-up with `config` and a fresh [[org.llm4s.agent.graph.RunId RunId]]. A `Left` carries no
   * thread id, so a conversation whose turn fails - cancelled by an interrupt included - is
   * forgotten once that turn has ended, as for the one-shot [[run]].
   */
  def runMultiTurn(first: String, followUps: Seq[String], config: RunConfig = RunConfig()): Result[AgentResult] =
    run(first, config).flatMap { opening =>
      forgottenOnFailure(opening.threadId, config) {
        followUps.foldLeft[Result[AgentResult]](Right(opening)) { (previous, query) =>
          previous.flatMap { result =>
            result.status match
              case AgentStatus.Completed(_) => continueConversation(result, query, config.withRunId(RunId.random()))
              case _                        => Right(result)
          }
        }
      }
    }

  /**
   * `turns`, run on `threadId`, a thread the caller cannot name: on a `Left`, which carries no thread
   * id, the thread is forgotten, its failed turn having ended. A cancelled turn whose provider ignores
   * its interrupt past [[AgentRun.Drain]] has not: its thread is `ThreadBusy`, and is left (logged at
   * WARN). A refused start created no thread, and forgetting an unknown thread is `Right`.
   */
  private def forgottenOnFailure(threadId: ThreadId, config: RunConfig)(
    turns: => Result[AgentResult]
  ): Result[AgentResult] =
    turns match
      case failed @ Left(_) =>
        AgentRun.uninterrupted(forget(threadId, config)).left.foreach { e =>
          Agent.logger.warn(
            s"The thread ${threadId.value} of a failed turn its caller cannot name was not forgotten: ${e.message}"
          )
        }
        failed
      case done => done

  /**
   * Removes `threadId` - its history, usage and event log - from the agent's runtime, so that its id
   * names a new thread again, and every child thread below it first ([[children]], design 4.41). Threads are kept
   * until forgotten, one-shot [[run]]s included. A child thread is refused (`GraphError.ChildThread`): forget its
   * parent, and a child with a live run refuses the whole call (`ThreadBusy`) with nothing removed.
   * Refused, with nothing removed, for a thread that a run holds - one active in this runtime, or one
   * holding a live claim on it in any runtime or process sharing the store (`GraphError.ThreadBusy`) - or
   * that belongs to another tenant than `config`'s (`GraphError.TenantMismatch`). Forgetting an unknown
   * thread is `Right(())`.
   */
  def forget(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Unit] =
    runtime.deleteThread(threadId, config)

  /**
   * Every direct child thread of `threadId` (design 4.41) - the threads its delegated tasks ran on - oldest first,
   * with each one's origin, status and latest checkpoint; a thread with no children, or none at all, has none. A
   * thread of another tenant than `config`'s is `GraphError.TenantMismatch`, as for the runtime's `history`. Read a
   * child's own history, checkpoints and events through the runtime by its id; a child thread cannot be run, resumed,
   * forked, updated or forgotten directly (`GraphError.ChildThread`): forgetting its parent forgets it.
   */
  @Experimental
  def children(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Vector[ChildThread]] =
    @scala.annotation.tailrec
    def pages(after: Option[ThreadId], found: Vector[ChildThread]): Result[Vector[ChildThread]] =
      runtime.children(threadId, after, Agent.ChildPage, config) match
        case Left(error)                                => Left(error)
        case Right(page) if page.size < Agent.ChildPage => Right(found ++ page)
        case Right(page)                                => pages(page.lastOption.map(_.threadId), found ++ page)
    pages(None, Vector.empty)

  /**
   * Follows one delegated child (design 4.47): replays the child thread's own log after `afterSeq`, then delivers its
   * new events - durable, and live from runs of this runtime - as `GraphRuntime.subscribe` does, with `nested = true`
   * so a grandchild's arrive as `StreamEvent.Nested`. Tenant-checked as every read of a thread is: a child of another
   * tenant than `config`'s is `GraphError.TenantMismatch`. `ref` is a `SubagentEvents.TaskStarted`'s or
   * `TaskEnded`'s child, or a `TaskOutcome`'s; a child's durable events outlive its live ones, so this is how to read
   * what a parent's merged stream reported as a gap (`Nested(child, LiveGap(0))`). The subscription is the thread's:
   * cancel it when done.
   */
  @Experimental
  def subscribeChild(ref: ChildRef, afterSeq: Long = 0L, config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[Subscription] =
    runtime.subscribe(ref.threadId, afterSeq, Agent.StreamCapacity, config, nested = true)(listener)

  /**
   * Continues `threadId`'s failed or interrupted run, re-running only failed or unstarted work.
   * Interrupting the calling thread cancels the turn, as for [[run]]. To close the run instead, running nothing
   * again, see [[abandon]].
   */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult] =
    blocking(startRecover(threadId, config))

  /** [[recover]], returning at once with the running turn - to cancel it, or to await its result. */
  def startRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentRun] =
    runtime.recover(threadId, loop.graph, configured(config)).map(agentRun(_, None, drain = false))

  /**
   * Closes `threadId`'s failed or interrupted run without continuing it
   * ([[https://github.com/llm4s/llm4s/issues/1750 #1750]]): the other answer to a run that was cancelled, failed or
   * crashed, for when [[recover]] would only run a misbehaving tool call again. Every tool call of the turn that has
   * no result gets one - its own, if the tool had finished when the run stopped, else `reason` - so the model never
   * sees a call with no result; no tool runs, and no model is called. The thread is then at rest, settled as a failure
   * (`RunOutcome.Failed("Abandoned", ...)`): [[run]] starts its next turn, in which the model reads `reason` as those
   * calls' results, and [[recover]] has nothing left to continue. A turn that stopped before its model answered has no
   * call to answer and is closed all the same.
   *
   * `reason` is model-visible text, so say what the model should conclude - the default, [[Agent.AbandonedResult]],
   * says the call did not complete. A blank one is refused (`ValidationError`). An abandoned call may still have had
   * its effect: a tool cut short by a cancel or a crash is not known to have done nothing.
   *
   * Only a thread with an incomplete run is abandoned, and anything else is refused with the thread unchanged: a
   * thread at rest or unknown is `GraphError.NothingToRecover`, a suspended one `GraphError.PendingInterrupts`
   * (answer it with [[resume]]), one a run still holds - in this runtime, or by a live claim in any runtime sharing
   * the store, a crashed process's until it lapses - `GraphError.ThreadBusy`, another tenant's
   * `GraphError.TenantMismatch`, and a child thread `GraphError.ChildThread`. So after [[AgentRun.cancel]], await the
   * run before abandoning its thread. The edit is made under the thread's claim, taken as `config.runId`, so it cannot
   * interleave with a run, and is committed as the run it closes: that run's last checkpoint and its `RunFailed` event.
   * A subagent the turn left running is settled as abandoned when the thread's next turn starts.
   *
   * A run a queue's worker started is abandoned the same way, once its worker has died and its claim has lapsed -
   * while the worker runs it the thread is `ThreadBusy`, and the queue's own cancel
   * ([[org.llm4s.agent.service.RunSubmitter.cancel RunSubmitter.cancel]]) is what stops it. The entry is then closed
   * by the next worker to take it, `EntryOutcome.Failed(FailureReason.Abandoned)`, with nothing run again - also when
   * the thread has taken other turns in between - and the entries queued behind it on the thread run after it.
   */
  @Experimental
  def abandon(
    threadId: ThreadId,
    reason: String = Agent.AbandonedResult,
    config: RunConfig = RunConfig()
  ): Result[Unit] =
    if reason.trim.isEmpty then Left(ValidationError("reason", "the reason recorded as a tool call's result is blank"))
    else
      val settled = configured(config)
      runtime.abandon(
        threadId,
        loop.graph,
        RunOutcome.Failed(Agent.AbandonedKind, Agent.AbandonedMessage),
        Agent.AbandonedMessage,
        settled,
        amend = state => SettledTurn.closing(state, s"${settled.runId.value}/abandoned", reason)
      )

  /**
   * Answers some of `threadId`'s pending approvals, questions and breakpoints - built with
   * [[AgentResult.approve]], [[AgentResult.reject]], [[AgentResult.edit]], [[AgentResult.reply]] and
   * [[AgentResult.proceed]] - and continues. Unanswered ones stay pending. Interrupting the calling
   * thread cancels the turn, as for [[run]].
   *
   * A subagent's interrupt (`Suspension.delegated`) is answered by its composite id: the answers are
   * grouped by the parent interrupt they go through and reach the child when its `task` call resumes (design 4.46).
   * A composite id that is not pending is refused (`GraphError.InvalidResume`), as any other interrupt is.
   */
  @Experimental
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[AgentResult] =
    blocking(startResume(threadId, answers, config))

  /** [[resume]], returning at once with the running turn - to cancel it, or to await its result. */
  @Experimental
  def startResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[AgentRun] =
    routed(threadId, answers, config).flatMap(grouped =>
      runtime.resume(threadId, loop.graph, grouped, configured(config)).map(agentRun(_, None, drain = false))
    )

  /**
   * `answers` as the runtime takes them (design 4.46): composite answers to delegated interrupts, checked against the
   * suspended thread's `delegated` interrupts, grouped into the `DelegatedAnswers` of the `task` call each goes
   * through. Answers with no composite id are passed as they are, for the runtime to check.
   */
  private def routed(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig
  ): Result[Map[InterruptId, ujson.Value]] =
    if !answers.keys.exists(_.value.contains(DelegatedAnswers.Separator)) then Right(answers)
    else
      for
        pending <- delegatedPending(threadId, config)
        _ <- pending.map(DelegatedAnswers.problems(answers, _)).filter(_.nonEmpty) match
          case Some(problems) => Left(GraphError.InvalidResume(loop.graph.id, problems.toList))
          case None           => Right(())
        grouped <- DelegatedAnswers.group(answers)
      yield grouped

  /** The suspended thread's delegated interrupts; `None` when it is not suspended, which the runtime then refuses. */
  private def delegatedPending(
    threadId: ThreadId,
    config: RunConfig
  ): Result[Option[Vector[(InterruptId, DelegatedRequest)]]] =
    runtime.childAtRest(threadId, loop.graph, configured(config)).flatMap {
      case Some((checkpoint, exec)) if checkpoint.status == CheckpointStatus.Suspended =>
        AgentRun.status(runtime.suspendedResult(loop.graph, exec), isBlock = false, loop).map {
          case AgentStatus.Suspended(suspension) => Some(suspension.delegated)
          case _                                 => None
        }
      case _ => Right(None)
    }

  /**
   * Starts a turn on `threadId` as [[run]] does, returning at once with the running turn - to
   * cancel it, or to await its result.
   */
  def start(
    threadId: ThreadId,
    query: String,
    config: RunConfig = RunConfig(),
    history: Seq[Message] = Nil
  ): Result[AgentRun] =
    startWith(threadId, query, config, history, None)

  /**
   * [[start]], with `listener` subscribed before the turn begins, so it receives every event of the
   * turn - live text deltas included - until the turn's terminal event, or a `Disconnected` if it
   * fell behind or threw; the subscription then ends itself. A turn that ends without a terminal
   * event (a crash, or a failed commit) ends it as soon as it has delivered the turn's last event,
   * at the run's end-of-run barrier. A refused start
   * (a blank query, a busy thread, ...) is `Left`, and the listener hears nothing. The run's
   * [[AgentRun.await]] returns only once `listener` has returned from the turn's last event.
   *
   * With `nested`, `listener` also receives every event of each child the turn delegates to (design 4.47), as
   * `StreamEvent.Nested(child, event)` after the turn's `SubagentEvents.TaskStarted` for it - a grandchild's nested
   * twice - in the subscription's live slots, so an overflow is a `LiveGap`. Match them with `EventHandlers.onChild`.
   */
  @Experimental
  def stream(
    threadId: ThreadId,
    query: String,
    config: RunConfig = RunConfig(),
    history: Seq[Message] = Nil,
    nested: Boolean = false
  )(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    startWith(threadId, query, config, history, Some(Listening(listener, NoEnd, drain = true, nested)))

  /** [[startResume]] with `listener` subscribed first; see [[stream]]. */
  @Experimental
  def streamResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig(),
    nested: Boolean = false
  )(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    resumeWith(threadId, answers, config, Listening(listener, NoEnd, drain = true, nested))

  /** [[startRecover]] with `listener` subscribed first; see [[stream]]. */
  @Experimental
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig(), nested: Boolean = false)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    recoverWith(threadId, config, Listening(listener, NoEnd, drain = true, nested))

  /**
   * [[stream]], calling `onEnd` once when the subscription ends - after the terminal event or a
   * `Disconnected` has been passed to `listener`, or, for a turn that ends without a terminal event,
   * once the subscription has delivered the turn's last event. For bridges (the fs2 and ZIO streams) that must
   * end without a terminal event too. Not called for a refused start. Unlike [[stream]]'s, the run's
   * `await` does not wait for `listener`: a bridge's release cancels and awaits the run while its
   * own listener may still be delivering.
   */
  private[llm4s] def streamEnding(
    threadId: ThreadId,
    query: String,
    config: RunConfig,
    history: Seq[Message],
    onEnd: () => Unit
  )(listener: StreamEvent => Unit): Result[AgentRun] =
    startWith(threadId, query, config, history, Some(Listening(listener, onEnd, drain = false)))

  /** [[streamResume]] with `onEnd`; see [[streamEnding]]. */
  private[llm4s] def streamResumeEnding(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig,
    onEnd: () => Unit
  )(listener: StreamEvent => Unit): Result[AgentRun] =
    resumeWith(threadId, answers, config, Listening(listener, onEnd, drain = false))

  /** [[streamRecover]] with `onEnd`; see [[streamEnding]]. */
  private[llm4s] def streamRecoverEnding(threadId: ThreadId, config: RunConfig, onEnd: () => Unit)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    recoverWith(threadId, config, Listening(listener, onEnd, drain = false))

  private val NoEnd: () => Unit = () => ()

  /**
   * A run's listener, the scope end's callback, whether the run's `await` waits for the listener, and whether it takes
   * delegated children's events.
   */
  final private case class Listening(
    listener: StreamEvent => Unit,
    onEnd: () => Unit,
    drain: Boolean,
    nested: Boolean = false
  )

  private def resumeWith(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig,
    listening: Listening
  ): Result[AgentRun] =
    routed(threadId, answers, config).flatMap { grouped =>
      val (observer, scope) = observed(config, listening)
      runtime
        .resume(threadId, loop.graph, grouped, configured(config), observer = Some(observer))
        .map(agentRun(_, Some(scope), listening.drain))
    }

  /**
   * Starts a turn with `begin` and awaits it. A caller already interrupted starts nothing. An
   * interrupted wait cancels the turn and returns once it has ended, waiting up to [[AgentRun.Drain]]
   * for the turn to end, so a blocking call - `run` inside a graph node whose run is cancelled - does
   * not leave its turn running and the thread is free for [[recover]]. The wait is bounded so that a
   * provider ignoring its
   * interrupt cannot hang a cancelled caller; such a turn is left to end on its own (see
   * [[AgentRun.cancelAndAwaitEnd]]). A turn that ended is awaited once more
   * ([[AgentRun.awaitEnded]]), which detaches its tracing; a cancel the turn's commit beat leaves it
   * `Right` - its real outcome - and that is returned. Either way the interrupt flag stays set.
   */
  private def blocking(begin: => Result[AgentRun]): Result[AgentResult] =
    if Thread.currentThread().isInterrupted then Left(CancelledError("agent turn"))
    else begin.flatMap(Agent.awaitBlocking)

  private def recoverWith(threadId: ThreadId, config: RunConfig, listening: Listening): Result[AgentRun] =
    val (observer, scope) = observed(config, listening)
    runtime
      .recover(threadId, loop.graph, configured(config), observer = Some(observer))
      .map(agentRun(_, Some(scope), listening.drain))

  private def startWith(
    threadId: ThreadId,
    query: String,
    config: RunConfig,
    history: Seq[Message],
    listening: Option[Listening]
  ): Result[AgentRun] =
    // refused before any thread is claimed: stored, a blank query would fail every model call after it
    if query.trim.isEmpty then Left(ValidationError("query", "the query is blank"))
    else startInput(threadId, AgentInput(query, history.toVector), config, listening)

  /**
   * Starts a turn with `input`, admitting its query first (#2854): nothing of the turn is stored before every `admit`
   * stage has run, a rewritten query is the only one stored, and a refused one starts a turn that holds no query and
   * blocks at once, its reason on the returned run's result only. An input that records its admission - one a
   * queue's submitter admitted - is started as it is.
   *
   * An input that imports `history` has every imported user message admitted with the query (#3235); a refusal of
   * either ends the turn `Blocked` with no thread created ([[refusedImport]]), so the import can be tried again on the
   * same thread id. A queued input refused at submission imports nothing.
   */
  private def startInput(
    threadId: ThreadId,
    input: AgentInput,
    config: RunConfig,
    listening: Option[Listening]
  ): Result[AgentRun] =
    // an input a submitter refused holds no query, and imports nothing it did not admit (#3235)
    val history = if input.refusedBy.isDefined then Vector.empty else input.history
    for
      _        <- if history.isEmpty then Right(()) else importable(history)
      admitted <- admitting(threadId, input.withHistory(history), config)
      watching = listening.map(observed(config, _))
      observer = watching.map(_._1)
      run <-
        if history.isEmpty then
          runtime
            .start(threadId, loop.graph, admitted.input, configured(config), observer = observer)
            .map(agentRun(_, watching.map(_._2), listening.exists(_.drain), refused = admitted.refused))
        else
          admitted.refused match
            case Some(blocked) => refusedImport(threadId, admitted.input, config, watching, listening, blocked)
            case None =>
              runtime
                .startNew(
                  threadId,
                  loop.graph,
                  admitted.input,
                  configured(config),
                  Agent.notNew,
                  observer
                )
                .map(agentRun(_, watching.map(_._2), listening.exists(_.drain)))
    yield run

  /**
   * A history import refused at admission (#3235): the turn ends `AgentStatus.Blocked`, as any refused query does, but
   * nothing of it reaches the agent's store, so no thread is created and the caller can import again on `threadId`.
   * The blocked turn - which holds no query and no history - runs on a runtime of its own, in memory, discarded with
   * the returned run. A thread that already exists is refused as an import onto it always is.
   */
  private def refusedImport(
    threadId: ThreadId,
    input: AgentInput,
    config: RunConfig,
    watching: Option[(Observer, RunScope)],
    listening: Option[Listening],
    blocked: GuardrailBlocked
  ): Result[AgentRun] =
    runtime.storedGraphId(threadId, config).flatMap {
      case Some(_) => Left(Agent.notNew)
      case None =>
        val detached = GraphRuntime.inMemory()
        detached
          .start(threadId, loop.graph, input, configured(config), observer = watching.map(_._1))
          .map { handle =>
            AgentRun(
              handle,
              loop,
              id,
              detached,
              tracing,
              watching.map(_._2),
              listening.exists(_.drain),
              None,
              handle.traceContext,
              Some(blocked)
            )
          }
    }

  /** [[start]] with an input a queue held (W2): admitted now unless its submitter admitted it. */
  private[agent] def startQueued(threadId: ThreadId, input: AgentInput, config: RunConfig): Result[AgentRun] =
    if input.query.trim.isEmpty && input.refusedBy.isEmpty then Left(ValidationError("query", "the query is blank"))
    else startInput(threadId, input, config, None)

  /**
   * `input` after the admission stage: the root's `admit` stack, and the stack of the agent the thread is with. That
   * agent is read from the thread before the turn claims it, which only a family with a handoff target needs; a
   * thread that cannot be read is admitted by the root alone, and the start that follows reports why.
   */
  private def admitting(threadId: ThreadId, input: AgentInput, config: RunConfig): Result[ToolLoop.Admitted] =
    val settled = configured(config)
    val active =
      if !loop.hasTargets then None
      else
        runtime
          .childAtRest(threadId, loop.graph, settled)
          .toOption
          .flatten
          .flatMap((_, execution) => execution.state.get(LoopKeys.activeAgent).toOption.flatten)
    loop.admit(input, active, threadId, settled)

  /**
   * Runs the admission stage on `query` and starts nothing (#2854): for a caller that hands the turn to something
   * that stores it before this agent runs it - a run queue above all. A query put on a queue as it is submitted is
   * stored there before any guardrail has seen it; admit it first and submit what comes back
   * ([[org.llm4s.agent.service.QueuedInput.admitted QueuedInput.admitted]]), and the queue holds the query only as
   * the stages left it, or, for a refused one, no query at all.
   *
   *   - `AdmittedQuery.Accepted(input)`: the query passed, `input` holding it as rewritten.
   *   - `AdmittedQuery.Refused(input, guardrail, reason)`: a stage refused it. `reason` is here and nowhere else;
   *     `input` holds no query. Submitting it runs a turn that ends `AgentStatus.Blocked(guardrail, None)`, which
   *     records the refusal on the thread; not submitting it leaves no record at all.
   *   - `Left`: a stage failed, or `query` or the stages' result is blank.
   *
   * The worker that takes the entry does not run the stages again. `threadId` and `config` are what the stages see as
   * the turn's thread, tenant and principal, and decide which agent's stages run after a handoff.
   */
  @Experimental
  def admit(
    threadId: ThreadId,
    query: String,
    config: RunConfig = RunConfig(),
    history: Seq[Message] = Nil
  ): Result[AdmittedQuery] =
    if query.trim.isEmpty then Left(ValidationError("query", "the query is blank"))
    else
      val checked = if history.isEmpty then Right(()) else importable(history)
      checked.flatMap(_ => admitting(threadId, AgentInput(query, history.toVector), config)).map { admitted =>
        admitted.refused match
          case Some(blocked) =>
            AdmittedQuery.Refused(admitted.input, blocked.guardrail, blocked.reason.getOrElse(blocked.message))
          case None => AdmittedQuery.Accepted(admitted.input)
      }

  /**
   * [[start]] on `threadId` only if the thread is new, checked atomically at admission: an existing thread is
   * `GraphError.ThreadExists`, no run started. For `BoundAgent.run` on a caller-chosen thread (#2077), which must
   * never start a second turn on a thread another caller has created since its read, and which records the run's
   * identity on the thread with `record`, written with its first checkpoint (#2086).
   */
  private[agent] def startNew(
    threadId: ThreadId,
    query: String,
    config: RunConfig,
    record: StateUpdate = StateUpdate.empty
  ): Result[AgentRun] =
    if query.trim.isEmpty then Left(ValidationError("query", "the query is blank"))
    else
      // admitted before it is stored, as any turn's query is (#2854); a new thread is with the root
      loop.admit(AgentInput(query), None, threadId, configured(config)).flatMap { admitted =>
        runtime
          .startNew(
            threadId,
            loop.graph,
            admitted.input,
            configured(config),
            GraphError.ThreadExists(threadId.value),
            amend = _ => Right(record)
          )
          .map(agentRun(_, None, drain = false, refused = admitted.refused))
      }

  /**
   * Whether this agent's graph can take `checkpoint` on as a run would (Stage 5 W2, design 4.56): a worker leaves an
   * entry it cannot run - another graph's thread, or a run in flight at another graph version - for a worker that can.
   */
  private[agent] def canContinue(checkpoint: Checkpoint): Result[Unit] = loop.graph.canContinue(checkpoint)

  /**
   * Settles `threadId` - its latest checkpoint incomplete and committed by one of `runs` - as `outcome`, with the
   * history closed: each tool call left without a result gets `result` as its synthetic one ([[SettledTurn]]), and a
   * `Failed` checkpoint with a `RunFailed(message)` event is committed as run `config.runId` under the thread's claim
   * (`GraphRuntime.settleQueued`). For a worker settling an entry whose attempts ran out (W2), and W3's cancel of a
   * queued run.
   */
  private[agent] def settleQueued(
    threadId: ThreadId,
    runs: Set[RunId],
    outcome: RunOutcome,
    message: String,
    result: String,
    config: RunConfig
  ): Result[Unit] =
    runtime.settleQueued(
      threadId,
      loop.graph,
      outcome,
      message,
      configured(config),
      amend = state => SettledTurn.closing(state, s"${config.runId.value}/settled", result),
      owns = checkpoint => runs.contains(RunId(checkpoint.runId))
    )

  /** An observer whose listener is `listening`'s scoped to the run `config` starts, calling its `onEnd` at the scope's end. */
  private def observed(config: RunConfig, listening: Listening): (Observer, RunScope) =
    val scope = RunScope(config.runId, listening.listener, listening.onEnd)
    (Observer(Agent.StreamCapacity, scope, listening.nested), scope)

  /** History must be a valid conversation without system messages; checked before any thread is claimed. */
  private def importable(history: Seq[Message]): Result[Unit] =
    if history.exists { case _: SystemMessage => true; case _ => false } then
      Left(ValidationError("history", "system messages are not imported; prompts belong to agents"))
    else Message.validateConversation(history.toList)

  /**
   * The turn `handle` runs. Its tracing continues the trace context the run's admission settled
   * ([[RunHandle.traceContext]], design 4.58): a run's own `RunConfig.traceContext`, else - for a resume or recover -
   * the trace the work started in, read from the checkpoint the run was admitted over, so every run of one piece of
   * work, and the child a suspended `task` call started, is in one trace.
   */
  private[agent] def agentRun(
    handle: RunHandle[TurnOutput],
    scope: Option[RunScope],
    drain: Boolean,
    parent: Option[(ThreadId, RunId)] = None,
    refused: Option[GuardrailBlocked] = None
  ): AgentRun =
    AgentRun(handle, loop, id, runtime, tracing, scope, drain, parent, handle.traceContext, refused)

/**
 * A turn's query after the admission stage, from [[Agent.admit]] (#2854): what to hand to whatever stores the turn
 * before the agent runs it. Either way [[input]] holds nothing of the query as it was submitted.
 */
@Experimental
enum AdmittedQuery:

  /** The turn's input after admission: give it to `QueuedInput.admitted`. */
  def input: AgentInput

  /** The query passed: `input` holds it as the stages left it. */
  case Accepted(input: AgentInput)

  /**
   * A stage refused the query: `guardrail` is its name - a blocking middleware's id - and `reason` what it gave,
   * which is nowhere else. `input` holds no query: a turn started with it ends `AgentStatus.Blocked(guardrail, None)`.
   */
  case Refused(input: AgentInput, guardrail: String, reason: String)

object Agent:
  private val logger = LoggerFactory.getLogger(classOf[Agent])

  /** A history import onto a thread that exists. */
  private def notNew: ValidationError = ValidationError("history", "history is imported only into a new thread")

  /**
   * Awaits `run`, which has begun, as [[Agent.blocking]] does: an interrupted wait - also one interrupted before it
   * began - cancels the run and waits up to [[AgentRun.Drain]] for it to end, returning its real outcome if its commit
   * beat the cancel, else the `CancelledError`, with the interrupt flag kept. The child runner awaits a child with it
   * (design 4.42), so a child admitted just before its parent's cancel is still cancelled.
   */
  private[agent] def awaitBlocking(run: AgentRun): Result[AgentResult] =
    run.await() match
      case cancelled @ Left(_: CancelledError) =>
        if run.cancelAndAwaitEnd() then
          run.awaitEnded() match
            case outcome @ Right(_) => outcome
            case Left(_)            => cancelled
        else cancelled
      case other => other

  /** Model calls per turn when [[AgentBuilder.withMaxSteps]] is not set. */
  val DefaultMaxSteps: Int = 50

  /** The result [[Agent.abandon]] records for a tool call that had none, unless the caller gives its own. */
  val AbandonedResult: String =
    "abandoned: the run stopped before this tool call returned a result, and the call was not run again"

  /** The kind of the `RunOutcome.Failed` an abandoned run is settled with ([[Agent.abandon]]). */
  val AbandonedKind: String = "Abandoned"

  /** The failure an abandoned run is settled with: its `RunFailed` event's and its `RunOutcome.Failed`'s message. */
  private val AbandonedMessage: String = "abandoned: the run was closed by its thread's owner rather than recovered"

  /** How many children [[Agent.children]] reads from the runtime at a time. */
  private val ChildPage: Int = 256

  /** Queue size of a `stream*` or `subscribe` subscription. */
  val StreamCapacity: Int = 1024

  /** A builder for an agent with id `id` (`[a-zA-Z0-9_-]{1,52}`, checked by `build()`) calling `client`. */
  def builder(id: String, client: LLMClient): AgentBuilder = AgentBuilder(id, client)
