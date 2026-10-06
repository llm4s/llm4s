package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.agent.graph.toolloop.{ LoopKeys, Messages, StoredMessage, ToolLoop, TurnOutcome, TurnOutput }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, UserMessage }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A running agent turn, from [[Agent.start]]: cancel it, or await its [[AgentResult]]. The turn runs
 * on its own thread, owned by the runtime, whether or not anyone awaits it.
 */
final class AgentRun private[agent] (
  handle: RunHandle[TurnOutput],
  loop: ToolLoop,
  root: AgentId,
  runtime: GraphRuntime,
  tracing: Option[AgentTracing],
  listening: Option[RunScope]
):
  // the listener scopes `await` drains: the `stream*` listener's, and every `subscribe`'s
  private val scopes = new ConcurrentLinkedQueue[RunScope]()
  listening.foreach(scopes.add)

  def threadId: ThreadId = handle.threadId
  def runId: RunId       = handle.runId

  /** Non-blocking: `Running` until the turn ends, then how it ended. */
  def status: RunStatus = handle.status

  /** Cancels the turn; see [[org.llm4s.agent.graph.RunHandle.cancel]]. The thread is left for `recover`. */
  def cancel(): Unit = handle.cancel()

  /**
   * Subscribes `listener` to this turn's events: its durable events replayed from the turn's start,
   * then live; its live events from now on (a live event sent before this call is missed - use
   * [[Agent.stream]] to receive every one). Run-scoped: nothing of another run on the thread is
   * delivered, and the subscription ends itself after the turn's terminal event, or after a
   * `Disconnected` - which reaches the listener only if it fell behind (`Lagging`) or threw. A turn
   * that ends without a terminal event (a crash, or a failed commit) ends the subscription once it
   * has delivered what it had. [[await]] returns only once `listener` has returned from the turn's
   * last event, within [[AgentRun.Drain]].
   */
  def subscribe(capacity: Int = Agent.StreamCapacity)(listener: StreamEvent => Unit): Result[Subscription] =
    val scope = RunScope(runId, listener)
    handle.subscribe(capacity)(scope).map { s =>
      scope.attach(s)
      RunScope.watch(handle, scope)
      scopes.add(scope)
      s
    }

  /**
   * Blocks until the turn ends, then returns its result - `Suspended` included - or the error it
   * failed with; the outcome is retained, so every call made once the turn has ended returns the
   * same value. A call whose awaiting thread is interrupted before then returns
   * `Left(CancelledError)` instead, with the interrupt flag still set, and the turn keeps running: a
   * later call returns its outcome, and only [[cancel]] stops it. With tracing, the turn's trace is
   * complete when a call returns the turn's outcome.
   *
   * With a listener - from [[Agent.stream]], [[Agent.streamResume]], [[Agent.streamRecover]] or
   * [[subscribe]] - a call that returns the outcome returns only once each listener has returned
   * from the turn's last event (its terminal event, a `Disconnected`, or, for a turn that ended
   * without a terminal event, its last delivered one), waiting at most [[AgentRun.Drain]] in all;
   * past that it logs a WARN and returns. A call made from a listener does not wait for that
   * listener.
   */
  def await(): Result[AgentResult] =
    val ended = handle.await()
    if handle.status != RunStatus.Running then
      drain()
      tracing.foreach(_.detach())
    for
      result <- ended
      // only a kernel Block - the run closed its thread Failed - is a blocked turn; a GuardrailBlocked
      // that failed the run another way (from a model or tool wrapper) leaves it for recover, so it is Left
      isBlock <- result match
        case RunResult.Failed(_, _: GuardrailBlocked) => runtime.endedBlocked(threadId, runId)
        case _                                        => Right(false)
      status <- AgentRun.status(result, isBlock, loop)
      agentResult <- result match
        case RunResult.Completed(state, output, _) => summary(state, Some(output.activeAgent), status)
        case suspended: RunResult.Suspended        => summary(suspended.state, None, status)
        // a blocked turn: the thread as the Block committed it - an input block stores nothing of the
        // turn, an output block removes it - with the usage the turn's model calls added
        case RunResult.Failed(state, _) => summary(state, None, status)
    yield agentResult

  /** Waits, within [[AgentRun.Drain]] in all, for every listener scope to end; see [[await]]. */
  private def drain(): Unit =
    val deadline = System.nanoTime() + AgentRun.Drain.toNanos
    @tailrec def next(open: List[RunScope]): Unit = open match
      case Nil => ()
      case scope :: rest =>
        scope.awaitEnd(math.max(0L, deadline - System.nanoTime()).nanos) match
          case Left(_)     => Thread.currentThread().interrupt() // stop waiting; the outcome is still returned
          case Right(true) => next(rest)
          case Right(false) =>
            if !scope.isEnded && System.nanoTime() >= deadline then
              AgentRun.logger.warn(
                s"A listener of run ${runId.value} on ${threadId.value} did not return from the run's last event within ${AgentRun.Drain}; await returns without it"
              )
            next(rest)
    next(scopes.asScala.toList)

  /** The turn's result: `state`'s messages and usage, with `active` or else the thread's active agent. */
  private def summary(state: ThreadState, active: Option[AgentId], status: AgentStatus): Result[AgentResult] =
    for
      messages <- state.get(Messages.key).map(_.map(_.message))
      usage    <- state.get(LoopKeys.usage)
      stored   <- state.get(LoopKeys.activeAgent)
    yield AgentResult(threadId, runId, active.orElse(stored).getOrElse(root), status, messages, usage)

private[agent] object AgentRun:
  private val logger = LoggerFactory.getLogger(classOf[AgentRun])

  /** How long [[AgentRun.await]] waits, after the run ends, for its listeners to return from its last event. */
  val Drain: FiniteDuration = 5.seconds

  /**
   * `handle` as an agent run, traced to `tracing` when given. `scope`, when given, is the listener
   * of the observer the run was admitted with, and ends the handle's observation after the run;
   * `await` drains it when `drain` is set. The bridges (fs2, ZIO) pass `drain = false`: they end on
   * the scope's `onEnd`, and their release awaits the run without waiting on their own listener.
   */
  def apply(
    handle: RunHandle[TurnOutput],
    loop: ToolLoop,
    root: AgentId,
    runtime: GraphRuntime,
    tracing: Option[Tracing],
    scope: Option[RunScope],
    drain: Boolean
  ): AgentRun =
    scope.foreach { s =>
      handle.observation.foreach(s.attach)
      RunScope.watch(handle, s)
    }
    new AgentRun(
      handle,
      loop,
      root,
      runtime,
      tracing.map(AgentTracing(handle, root, loop, _)),
      scope.filter(_ => drain)
    )

  /**
   * How a turn ended, from its run's result: the status [[AgentRun.await]] reports, or the error it
   * returns. The one derivation of a turn's status - `await` and [[AgentTracing]] both use it.
   * `isBlock` is whether a failed result is the run's guardrail Block: `await` reads it from the
   * thread's closing checkpoint, tracing from the run's own `agent.guardrail_blocked` event. A
   * completed turn without an assistant message is a `ValidationError`.
   */
  def status(result: RunResult[TurnOutput], isBlock: Boolean, loop: ToolLoop): Result[AgentStatus] =
    result match
      case RunResult.Completed(state, output, _) =>
        output.outcome match
          case TurnOutcome.Completed =>
            state.get(Messages.key).flatMap { stored =>
              stored.reverseIterator
                .collectFirst { case StoredMessage(_, a: AssistantMessage) => AgentStatus.Completed(a.content) }
                .toRight(ValidationError("agent", "the turn completed without an assistant message"))
            }
          case TurnOutcome.StepLimitReached => Right(AgentStatus.StepLimitReached)
      case suspended: RunResult.Suspended =>
        for
          approvals <- loop.requests(suspended)
          questions <- loop.questions(suspended)
        yield AgentStatus.Suspended(approvals, questions)
      case RunResult.Failed(_, blocked: GuardrailBlocked) if isBlock =>
        Right(AgentStatus.Blocked(blocked.guardrail, blocked.reason))
      case RunResult.Failed(_, error) => Left(error)

  /** The current turn's messages: from the last user message on. */
  def turnMessages(state: ThreadState): Result[Vector[Message]] =
    state.get(Messages.key).map { stored =>
      val all = stored.map(_.message)
      all.lastIndexWhere { case _: UserMessage => true; case _ => false } match
        case -1 => all
        case at => all.drop(at)
    }
