package org.llm4s.agent

import org.llm4s.agent.delegation.{ DelegatedAnswers, DelegatedRequest }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ AgentInput, ToolLoop, TurnOutput }
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
 * Every run is a turn on a thread. A run that reaches the graph's end is `Right` with status
 * `Completed`, `StepLimitReached` or `TokenLimitReached`; one a guardrail blocks is `Right` with
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
final class Agent private[agent] (
  val id: AgentId,
  val loop: ToolLoop,
  private[agent] val runtime: GraphRuntime,
  tracing: Option[Tracing],
  interruptBefore: Set[NodeId] = Set.empty,
  interruptAfter: Set[NodeId] = Set.empty,
  retention: Option[RetentionPolicy] = None,
  childRetention: Option[Int] = None
):

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
   * runs with `config`, each follow-up with `config` and a fresh [[RunId]]. A `Left` carries no
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
  def children(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Vector[ChildThread]] =
    @scala.annotation.tailrec
    def pages(after: Option[ThreadId], found: Vector[ChildThread]): Result[Vector[ChildThread]] =
      runtime.children(threadId, after, Agent.ChildPage, config) match
        case Left(error)                                => Left(error)
        case Right(page) if page.size < Agent.ChildPage => Right(found ++ page)
        case Right(page)                                => pages(page.lastOption.map(_.threadId), found ++ page)
    pages(None, Vector.empty)

  /**
   * Continues `threadId`'s failed or interrupted run, re-running only failed or unstarted work.
   * Interrupting the calling thread cancels the turn, as for [[run]].
   */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult] =
    blocking(startRecover(threadId, config))

  /** [[recover]], returning at once with the running turn - to cancel it, or to await its result. */
  def startRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentRun] =
    runtime.recover(threadId, loop.graph, configured(config)).map(agentRun(_, None, drain = false))

  /**
   * Answers some of `threadId`'s pending approvals, questions and breakpoints - built with
   * [[AgentResult.approve]], [[AgentResult.reject]], [[AgentResult.edit]], [[AgentResult.reply]] and
   * [[AgentResult.proceed]] - and continues. Unanswered ones stay pending. Interrupting the calling
   * thread cancels the turn, as for [[run]].
   *
   * A subagent's interrupt (`AgentStatus.Suspended.delegated`) is answered by its composite id: the answers are
   * grouped by the parent interrupt they go through and reach the child when its `task` call resumes (design 4.46).
   * A composite id that is not pending is refused (`GraphError.InvalidResume`), as any other interrupt is.
   */
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[AgentResult] =
    blocking(startResume(threadId, answers, config))

  /** [[resume]], returning at once with the running turn - to cancel it, or to await its result. */
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
          case suspended: AgentStatus.Suspended => Some(suspended.delegated)
          case _                                => None
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
   */
  def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    startWith(threadId, query, config, history, Some(Listening(listener, NoEnd, drain = true)))

  /** [[startResume]] with `listener` subscribed first; see [[stream]]. */
  def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    resumeWith(threadId, answers, config, Listening(listener, NoEnd, drain = true))

  /** [[startRecover]] with `listener` subscribed first; see [[stream]]. */
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    recoverWith(threadId, config, Listening(listener, NoEnd, drain = true))

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

  /** A run's listener, the scope end's callback, and whether the run's `await` waits for the listener. */
  final private case class Listening(listener: StreamEvent => Unit, onEnd: () => Unit, drain: Boolean)

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
    val input    = AgentInput(query, history.toVector)
    val watching = listening.map(observed(config, _))
    val observer = watching.map(_._1)
    val started =
      // refused before any thread is claimed: stored, a blank query would fail every model call after it
      if query.trim.isEmpty then Left(ValidationError("query", "the query is blank"))
      else if history.isEmpty then runtime.start(threadId, loop.graph, input, configured(config), observer = observer)
      else
        importable(history).flatMap(_ =>
          runtime.startNew(
            threadId,
            loop.graph,
            input,
            configured(config),
            ValidationError("history", "history is imported only into a new thread"),
            observer
          )
        )
    started.map(agentRun(_, watching.map(_._2), listening.exists(_.drain)))

  /** An observer whose listener is `listening`'s scoped to the run `config` starts, calling its `onEnd` at the scope's end. */
  private def observed(config: RunConfig, listening: Listening): (Observer, RunScope) =
    val scope = RunScope(config.runId, listening.listener, listening.onEnd)
    (Observer(Agent.StreamCapacity, scope), scope)

  /** History must be a valid conversation without system messages; checked before any thread is claimed. */
  private def importable(history: Seq[Message]): Result[Unit] =
    if history.exists { case _: SystemMessage => true; case _ => false } then
      Left(ValidationError("history", "system messages are not imported; prompts belong to agents"))
    else Message.validateConversation(history.toList)

  private[agent] def agentRun(handle: RunHandle[TurnOutput], scope: Option[RunScope], drain: Boolean): AgentRun =
    AgentRun(handle, loop, id, runtime, tracing, scope, drain)

object Agent:
  private val logger = LoggerFactory.getLogger(classOf[Agent])

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

  /** How many children [[Agent.children]] reads from the runtime at a time. */
  private val ChildPage: Int = 256

  /** Queue size of a `stream*` or `subscribe` subscription. */
  val StreamCapacity: Int = 1024

  /** A builder for an agent with id `id` (`[a-zA-Z0-9_-]{1,52}`, checked by `build()`) calling `client`. */
  def builder(id: String, client: LLMClient): AgentBuilder = AgentBuilder(id, client)
