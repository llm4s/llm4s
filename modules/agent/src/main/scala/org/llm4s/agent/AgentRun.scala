package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.agent.graph.toolloop.{ LoopKeys, Messages, ToolLoop, TurnOutcome, TurnOutput }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, UserMessage }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

/**
 * A running agent turn, from [[Agent.start]]: cancel it, or await its [[AgentResult]]. The turn runs
 * on its own thread, owned by the runtime, whether or not anyone awaits it.
 */
final class AgentRun private[agent] (
  handle: RunHandle[TurnOutput],
  loop: ToolLoop,
  root: AgentId,
  runtime: GraphRuntime,
  tracing: Option[AgentTracing]
):

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
   * has delivered what it had.
   */
  def subscribe(capacity: Int = Agent.StreamCapacity)(listener: StreamEvent => Unit): Result[Subscription] =
    val scope = RunScope(runId, listener)
    handle.subscribe(capacity)(scope).map { s =>
      scope.attach(s)
      RunScope.watch(handle, scope)
      s
    }

  /**
   * Blocks until the turn ends, then returns its result - `Suspended` included - or the error it
   * failed with; the outcome is retained, so every call made once the turn has ended returns the
   * same value. A call whose awaiting thread is interrupted before then returns
   * `Left(CancelledError)` instead, with the interrupt flag still set, and the turn keeps running: a
   * later call returns its outcome, and only [[cancel]] stops it. With tracing, the turn's trace is
   * complete when a call returns the turn's outcome.
   */
  def await(): Result[AgentResult] =
    val ended = handle.await()
    if handle.status != RunStatus.Running then tracing.foreach(_.detach())
    ended.flatMap {
      case RunResult.Completed(state, output, _) => completed(state, output)
      case suspended: RunResult.Suspended        => this.suspended(suspended)
      // only a kernel Block - the run closed its thread Failed - is a blocked turn; a GuardrailBlocked
      // that failed the run another way (from a model or tool wrapper) leaves it for recover, so it is Left
      case RunResult.Failed(state, blocked: GuardrailBlocked) =>
        runtime.endedBlocked(threadId, runId).flatMap { isBlock =>
          if isBlock then snapshot(state, AgentStatus.Blocked(blocked.guardrail, blocked.reason)) else Left(blocked)
        }
      case RunResult.Failed(_, error) => Left(error)
    }

  private def completed(state: ThreadState, output: TurnOutput): Result[AgentResult] =
    for
      messages <- state.get(Messages.key).map(_.map(_.message))
      usage    <- state.get(LoopKeys.usage)
      status <- output.outcome match
        case TurnOutcome.Completed =>
          messages.reverseIterator
            .collectFirst { case a: AssistantMessage => AgentStatus.Completed(a.content) }
            .toRight(ValidationError("agent", "the turn completed without an assistant message"))
        case TurnOutcome.StepLimitReached => Right(AgentStatus.StepLimitReached)
    yield AgentResult(threadId, runId, output.activeAgent, status, messages, usage)

  /**
   * A blocked turn: the thread as the Block committed it - an input block stores nothing of the turn,
   * an output block removes it - with the usage the turn's model calls added.
   */
  private def snapshot(state: ThreadState, status: AgentStatus): Result[AgentResult] =
    for
      messages <- state.get(Messages.key).map(_.map(_.message))
      usage    <- state.get(LoopKeys.usage)
      active   <- state.get(LoopKeys.activeAgent)
    yield AgentResult(threadId, runId, active.getOrElse(root), status, messages, usage)

  private def suspended(result: RunResult.Suspended): Result[AgentResult] =
    for
      approvals <- loop.requests(result)
      questions <- loop.questions(result)
      messages  <- result.state.get(Messages.key).map(_.map(_.message))
      usage     <- result.state.get(LoopKeys.usage)
      active    <- result.state.get(LoopKeys.activeAgent)
    yield AgentResult(
      threadId,
      runId,
      active.getOrElse(root),
      AgentStatus.Suspended(approvals, questions),
      messages,
      usage
    )

private[agent] object AgentRun:

  /**
   * `handle` as an agent run, traced to `tracing` when given. `scope`, when given, is the listener
   * of the observer the run was admitted with, and ends the handle's observation after the run.
   */
  def apply(
    handle: RunHandle[TurnOutput],
    loop: ToolLoop,
    root: AgentId,
    runtime: GraphRuntime,
    tracing: Option[Tracing],
    scope: Option[RunScope]
  ): AgentRun =
    scope.foreach { s =>
      handle.observation.foreach(s.attach)
      RunScope.watch(handle, s)
    }
    new AgentRun(handle, loop, root, runtime, tracing.map(AgentTracing(handle, root, _)))

  /** The current turn's messages: from the last user message on. */
  def turnMessages(state: ThreadState): Result[Vector[Message]] =
    state.get(Messages.key).map { stored =>
      val all = stored.map(_.message)
      all.lastIndexWhere { case _: UserMessage => true; case _ => false } match
        case -1 => all
        case at => all.drop(at)
    }
