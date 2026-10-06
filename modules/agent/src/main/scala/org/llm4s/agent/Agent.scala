package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ AgentInput, ToolLoop, TurnOutput }
import org.llm4s.error.ValidationError
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
   */
  def run(threadId: ThreadId, query: String, config: RunConfig, history: Seq[Message]): Result[AgentResult] =
    start(threadId, query, config, history).flatMap(_.await())

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

  /** Continues `threadId`'s failed or interrupted run, re-running only failed or unstarted work. */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult] =
    startRecover(threadId, config).flatMap(_.await())

  /** [[recover]], returning at once with the running turn - to cancel it, or to await its result. */
  def startRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentRun] =
    runtime.recover(threadId, loop.graph, config).map(agentRun(_, None))

  /**
   * Answers some of `threadId`'s pending approvals and questions - built with
   * [[AgentResult.approve]], [[AgentResult.reject]], [[AgentResult.edit]] and [[AgentResult.reply]]
   * - and continues. Unanswered ones stay pending.
   */
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[AgentResult] =
    startResume(threadId, answers, config).flatMap(_.await())

  /** [[resume]], returning at once with the running turn - to cancel it, or to await its result. */
  def startResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[AgentRun] =
    runtime.resume(threadId, loop.graph, answers, config).map(agentRun(_, None))

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
   * event (a crash, or a failed commit) ends it once it has delivered what it had. A refused start
   * (a blank query, a busy thread, ...) is `Left`, and the listener hears nothing.
   */
  def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    streamEnding(threadId, query, config, history, NoEnd)(listener)

  /** [[startResume]] with `listener` subscribed first; see [[stream]]. */
  def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    streamResumeEnding(threadId, answers, config, NoEnd)(listener)

  /** [[startRecover]] with `listener` subscribed first; see [[stream]]. */
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig())(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    streamRecoverEnding(threadId, config, NoEnd)(listener)

  /**
   * [[stream]], calling `onEnd` once when the subscription ends - after the terminal event or a
   * `Disconnected` has been passed to `listener`, or, for a turn that ends without a terminal event,
   * once the subscription has delivered what it had. For bridges (the fs2 and ZIO streams) that must
   * end without a terminal event too. Not called for a refused start.
   */
  private[llm4s] def streamEnding(
    threadId: ThreadId,
    query: String,
    config: RunConfig,
    history: Seq[Message],
    onEnd: () => Unit
  )(listener: StreamEvent => Unit): Result[AgentRun] =
    startWith(threadId, query, config, history, Some((listener, onEnd)))

  /** [[streamResume]] with `onEnd`; see [[streamEnding]]. */
  private[llm4s] def streamResumeEnding(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig,
    onEnd: () => Unit
  )(listener: StreamEvent => Unit): Result[AgentRun] =
    val (observer, scope) = observed(config, listener, onEnd)
    runtime.resume(threadId, loop.graph, answers, config, observer = Some(observer)).map(agentRun(_, Some(scope)))

  /** [[streamRecover]] with `onEnd`; see [[streamEnding]]. */
  private[llm4s] def streamRecoverEnding(threadId: ThreadId, config: RunConfig, onEnd: () => Unit)(
    listener: StreamEvent => Unit
  ): Result[AgentRun] =
    val (observer, scope) = observed(config, listener, onEnd)
    runtime.recover(threadId, loop.graph, config, observer = Some(observer)).map(agentRun(_, Some(scope)))

  private val NoEnd: () => Unit = () => ()

  private def startWith(
    threadId: ThreadId,
    query: String,
    config: RunConfig,
    history: Seq[Message],
    listener: Option[(StreamEvent => Unit, () => Unit)]
  ): Result[AgentRun] =
    val input    = AgentInput(query, history.toVector)
    val watching = listener.map((l, onEnd) => observed(config, l, onEnd))
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
    started.map(agentRun(_, watching.map(_._2)))

  /** An observer whose listener is `listener` scoped to the run `config` starts, calling `onEnd` at the scope's end. */
  private def observed(config: RunConfig, listener: StreamEvent => Unit, onEnd: () => Unit): (Observer, RunScope) =
    val scope = RunScope(config.runId, listener, onEnd)
    (Observer(Agent.StreamCapacity, scope), scope)

  /** History must be a valid conversation without system messages; checked before any thread is claimed. */
  private def importable(history: Seq[Message]): Result[Unit] =
    if history.exists { case _: SystemMessage => true; case _ => false } then
      Left(ValidationError("history", "system messages are not imported; prompts belong to agents"))
    else Message.validateConversation(history.toList)

  private def agentRun(handle: RunHandle[TurnOutput], scope: Option[RunScope]): AgentRun =
    AgentRun(handle, loop, id, runtime, tracing, scope)

object Agent:

  /** Model calls per turn when [[AgentBuilder.withMaxSteps]] is not set. */
  val DefaultMaxSteps: Int = 50

  /** Queue size of a `stream*` or `subscribe` subscription. */
  val StreamCapacity: Int = 1024

  /** A builder for an agent with id `id` (`[a-zA-Z0-9_-]{1,52}`, checked by `build()`) calling `client`. */
  def builder(id: String, client: LLMClient): AgentBuilder = AgentBuilder(id, client)
