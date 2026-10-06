package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.agent.graph.toolloop.{ LoopKeys, Messages, ToolLoop, TurnOutcome, TurnOutput }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CompletableFuture, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.duration.*
import scala.util.{ Failure, Try }

/**
 * A running agent turn, from [[Agent.start]]: cancel it, or await its [[AgentResult]]. The turn runs
 * on its own thread, owned by the runtime, whether or not anyone awaits it.
 */
final class AgentRun private[agent] (
  handle: RunHandle[TurnOutput],
  loop: ToolLoop,
  root: AgentId,
  runtime: GraphRuntime,
  tracing: Option[AgentRun.TracedRun]
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
   * delivered, and the subscription ends itself after the turn's terminal event. A `Disconnected`
   * reaches the listener only if it fell behind (`Lagging`) or threw.
   */
  def subscribe(capacity: Int = Agent.StreamCapacity)(listener: StreamEvent => Unit): Result[Subscription] =
    val scope = RunScope(runId, listener)
    handle.subscribe(capacity)(scope).map { s =>
      scope.attach(s)
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

  private val logger = LoggerFactory.getLogger(classOf[AgentRun])

  /** How long `await` waits, after the run ends, for its tracing subscription to deliver the run's last event. */
  private val TracingDrain: FiniteDuration = 5.seconds

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
    scope.foreach(s => handle.observation.foreach(s.attach))
    new AgentRun(handle, loop, root, runtime, tracing.map(TracedRun(handle, _)))

  /**
   * A run's tracing: a subscription to the run's thread from just before its claim, tracing only
   * this run's events. It cancels itself on the run's last event; `detach` waits up to
   * [[TracingDrain]] for that event to be traced, then cancels it, so a run's trace is complete
   * when `await` returns.
   */
  final class TracedRun(handle: RunHandle[?], tracing: Tracing):
    private val delivered    = new CompletableFuture[Unit]()
    private val subscription = new AtomicReference[Option[Subscription]](None)
    private val detached     = new AtomicBoolean(false)
    private val trace        = TracingSubscriber.listener(handle.threadId, tracing)

    handle
      .subscribe() {
        case event @ StreamEvent.Durable(record) if record.runId == handle.runId.value =>
          trace(event)
          if RunScope.terminal(record.event) then finish()
        case StreamEvent.Durable(_) => ()
        case event @ StreamEvent.Disconnected(_, _) =>
          trace(event)
          delivered.complete(()): Unit
        case _ => ()
      }
      .fold(
        // nothing to wait for: the run is untraced
        _ => delivered.complete(()): Unit,
        s =>
          subscription.set(Some(s))
          if delivered.isDone then s.cancel()
      )

    private def finish(): Unit =
      delivered.complete(()): Unit
      subscription.get.foreach(_.cancel())

    def detach(): Unit =
      if detached.compareAndSet(false, true) then
        CancelledError.catchInterrupt(Try(delivered.get(TracingDrain.toMillis, TimeUnit.MILLISECONDS))) match
          case Left(_) => Thread.currentThread().interrupt()
          case Right(Failure(_: TimeoutException)) =>
            logger.warn(
              s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} did not deliver the run's last event within $TracingDrain; its trace may be incomplete"
            )
          case Right(_) => ()
        subscription.get.foreach(_.cancel())
