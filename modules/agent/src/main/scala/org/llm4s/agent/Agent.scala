package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ AgentInput, ToolLoop, TurnOutput }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Message, SystemMessage }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

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
 * `Completed` or `StepLimitReached`; one a guardrail blocks is `Right` with `Blocked`, the blocked
 * turn absent from the thread; one that parks on approvals or questions is `Right` with
 * `Suspended`, and continues with [[resume]]. Anything else is `Left`: a blank query and the
 * runtime's refusals (`ThreadBusy`, `TenantMismatch`, `IncompleteRun`, `PendingInterrupts`, ...)
 * leave the thread unchanged, as does another middleware's `beforeAgent` or `afterAgent` failure
 * (a Block without a guardrail; a blank query or answer from a hook is one), which also ends the
 * run; a failed run - a provider error, a tool's `Fatal`, a model or tool wrapper's failure,
 * cancellation, a deadline - leaves the thread for [[recover]].
 */
final class Agent private[agent] (
  val id: AgentId,
  val loop: ToolLoop,
  runtime: GraphRuntime,
  tracing: Option[Tracing]
):

  /**
   * One turn on a new thread with a random id. The thread stays in the agent's runtime - on the
   * default in-memory runtime, in memory - until [[forget]] removes it; a caller that does not
   * continue the conversation should forget `result.threadId` when done.
   */
  def run(query: String, config: RunConfig = RunConfig()): Result[AgentResult] =
    run(ThreadId(java.util.UUID.randomUUID().toString), query, config, Nil)

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
   * interrupt flag still set, once the turn has ended, leaving the thread for [[recover]]. A caller
   * already interrupted gets that `Left` without a turn being started. To keep a turn running past an
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
   * status is not `Completed`; returns the last turn's result.
   */
  def runMultiTurn(first: String, followUps: Seq[String], config: RunConfig = RunConfig()): Result[AgentResult] =
    followUps.foldLeft(run(first, config)) { (previous, query) =>
      previous.flatMap { result =>
        result.status match
          case AgentStatus.Completed(_) => continueConversation(result, query, config)
          case _                        => Right(result)
      }
    }

  /**
   * Removes `threadId` - its history, usage and event log - from the agent's runtime, so that its id
   * names a new thread again. Threads are kept until forgotten, one-shot [[run]]s included.
   * Refused, with nothing removed, for a thread whose run is active in this runtime
   * (`GraphError.ThreadBusy`) or that belongs to another tenant than `config`'s
   * (`GraphError.TenantMismatch`). Forgetting an unknown thread is `Right(())`.
   */
  def forget(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Unit] =
    runtime.deleteThread(threadId, config)

  /**
   * Continues `threadId`'s failed or interrupted run, re-running only failed or unstarted work.
   * Interrupting the calling thread cancels the turn, as for [[run]].
   */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult] =
    blocking(startRecover(threadId, config))

  /** [[recover]], returning at once with the running turn - to cancel it, or to await its result. */
  def startRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentRun] =
    runtime.recover(threadId, loop.graph, config).map(agentRun(_, None, drain = false))

  /**
   * Answers some of `threadId`'s pending approvals and questions - built with
   * [[AgentResult.approve]], [[AgentResult.reject]], [[AgentResult.edit]] and [[AgentResult.reply]]
   * - and continues. Unanswered ones stay pending. Interrupting the calling thread cancels the turn,
   * as for [[run]].
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
    runtime.resume(threadId, loop.graph, answers, config).map(agentRun(_, None, drain = false))

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
    val (observer, scope) = observed(config, listening)
    runtime
      .resume(threadId, loop.graph, answers, config, observer = Some(observer))
      .map(agentRun(_, Some(scope), listening.drain))

  /**
   * Starts a turn with `begin` and awaits it. A caller already interrupted starts nothing. An
   * interrupted wait cancels the turn and returns once it has ended, so a blocking call - `run` inside
   * a graph node whose run is cancelled - never leaves its turn running, and the thread is free for
   * [[recover]]. Either way the interrupt flag stays set.
   */
  private def blocking(begin: => Result[AgentRun]): Result[AgentResult] =
    if Thread.currentThread().isInterrupted then Left(CancelledError("agent turn"))
    else
      begin.flatMap { run =>
        run.await() match
          case cancelled @ Left(_: CancelledError) =>
            run.cancelAndAwaitEnd()
            cancelled
          case other => other
      }

  private def recoverWith(threadId: ThreadId, config: RunConfig, listening: Listening): Result[AgentRun] =
    val (observer, scope) = observed(config, listening)
    runtime
      .recover(threadId, loop.graph, config, observer = Some(observer))
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
      else if history.isEmpty then runtime.start(threadId, loop.graph, input, config, observer = observer)
      else
        importable(history).flatMap(_ =>
          runtime.startNew(
            threadId,
            loop.graph,
            input,
            config,
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

  private def agentRun(handle: RunHandle[TurnOutput], scope: Option[RunScope], drain: Boolean): AgentRun =
    AgentRun(handle, loop, id, runtime, tracing, scope, drain)

object Agent:

  /** Model calls per turn when [[AgentBuilder.withMaxSteps]] is not set. */
  val DefaultMaxSteps: Int = 50

  /** Queue size of a `stream*` or `subscribe` subscription. */
  val StreamCapacity: Int = 1024

  /** A builder for an agent with id `id` (`[a-zA-Z0-9_-]{1,52}`, checked by `build()`) calling `client`. */
  def builder(id: String, client: LLMClient): AgentBuilder = AgentBuilder(id, client)
