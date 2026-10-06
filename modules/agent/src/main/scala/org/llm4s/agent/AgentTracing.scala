package org.llm4s.agent

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ LoopKeys, ToolLoop, TurnOutput }
import org.llm4s.types.Result
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.slf4j.LoggerFactory

import java.util.concurrent.{ CompletableFuture, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.duration.*
import scala.util.{ Failure, Try }

/**
 * One run's tracing. Subscribes to the run's events from its claim (durable replay; live events are
 * not traced) through a [[RunScope]], so nothing of another run on the thread is traced, and traces
 * each as `TracingSubscriber` does - `graph.*` custom events, with agent events named `agent.*` - plus each model call's
 * usage as `TokenUsageRecorded(usage, model, "agent_completion")`, which it also sums into the run's
 * own usage (the shape of `AgentResult.usage`, for this run's calls only). On the run's terminal event it
 * takes the run's result from its handle (set just after the closing commit) and traces one
 * [[TraceEvent.AgentRunEnded]], its status derived by [[AgentRun.status]] as `await` derives it; a
 * failed run also traces `ErrorOccurred`. A run that ends without a terminal event (a crash, or a
 * failed terminal commit) traces no `AgentRunEnded`: once the subscription has delivered what it
 * had, its error is traced as `ErrorOccurred` and logged at WARN. Tracing failures are logged at
 * WARN and never fail the run.
 */
final private[agent] class AgentTracing(
  handle: RunHandle[TurnOutput],
  root: AgentId,
  loop: ToolLoop,
  tracing: Tracing
):
  private val delivered    = new CompletableFuture[Unit]()
  private val detached     = new AtomicBoolean(false)
  private val blockedBy    = new AtomicReference[Option[String]](None)
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val terminalSeen = new AtomicBoolean(false)
  private val disconnected = new AtomicBoolean(false)
  // this run's own usage, from its ModelCallCompleted events; the thread's LoopKeys.usage spans every run
  private val usage = new AtomicReference(UsageSummary())

  // ends - once - after the terminal event is traced, after a Disconnected, or when the run lacks a terminal event
  private val scope = RunScope(handle.runId, onEvent, () => scopeEnded())

  handle
    .subscribe()(scope)
    .fold(
      // nothing to wait for: the run is untraced
      _ => delivered.complete(()): Unit,
      s =>
        subscription.set(Some(s))
        scope.attach(s)
        RunScope.watch(handle, scope)
    )

  private def onEvent(event: StreamEvent): Unit = event match
    case StreamEvent.Durable(record) =>
      trace(AgentTracing.toTrace(record))
      event match
        case AgentEvents.ModelCallCompleted(m) =>
          usage.updateAndGet(
            _.add(m.model, m.usage.map(_.toTokenUsage).getOrElse(TokenUsage(0, 0, 0)), m.estimatedCost)
          ): Unit
          m.usage.foreach(u => trace(TraceEvent.TokenUsageRecorded(u.toTokenUsage, m.model, "agent_completion")))
        case AgentEvents.GuardrailBlocked(g) => blockedBy.set(Some(g.guardrail))
        case _                               => ()
      if RunScope.terminal(record.event) then
        terminalSeen.set(true)
        ended(record.event)
    case StreamEvent.Disconnected(lastSeq, reason) =>
      disconnected.set(true)
      AgentTracing.logger.warn(
        s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} ended after seq $lastSeq: $reason"
      )
    case _ => ()

  /**
   * Traces the run's AgentRunEnded from its own result, which the run thread sets just after the
   * closing commit, with the status [[AgentRun.status]] derives - as `await` does - and the run's own
   * usage, summed from its `ModelCallCompleted` events (the thread's usage spans every run on it).
   */
  private def ended(terminal: RunEvent): Unit =
    val result = handle.await()
    val state  = result.toOption.map(AgentTracing.stateOf)
    val active = state.flatMap(_.get(LoopKeys.activeAgent).toOption.flatten).getOrElse(root)
    val status = result.flatMap(r => AgentRun.status(r, blockedBy.get.isDefined, loop))
    val label  = AgentTracing.label(terminal, status)
    val messages = status match
      case Right(_: AgentStatus.Blocked) | Left(_) => Vector.empty
      case Right(_) => state.flatMap(AgentRun.turnMessages(_).toOption).getOrElse(Vector.empty)
    status.left.foreach(error => if label == "failed" then traceError(error.message))
    trace(
      TraceEvent.AgentRunEnded(handle.threadId.value, handle.runId.value, active.value, label, messages, usage.get)
    )

  /**
   * The scope's end. After the terminal event (traced by [[ended]]) or a `Disconnected` (logged) it
   * only releases `detach`. Otherwise the run ended without a terminal event - it crashed, or its
   * terminal commit failed - and its result is already set (`RunScope.watch` ends the scope only
   * after it): warns, and traces its error as `ErrorOccurred`; it traces no `AgentRunEnded`.
   */
  private def scopeEnded(): Unit =
    if !terminalSeen.get && !disconnected.get then
      val error = handle.await() match
        case Right(RunResult.Failed(_, e)) => Some(e)
        case Left(e)                       => Some(e)
        case Right(_)                      => None
      AgentTracing.logger.warn(
        s"Run ${handle.runId.value} on ${handle.threadId.value} ended without a terminal event${error
            .fold("")(e => s": ${e.message}")}; its trace has no AgentRunEnded"
      )
      error.foreach(e => traceError(e.message))
    delivered.complete(()): Unit

  private def traceError(message: String): Unit =
    Try(tracing.traceError(new RuntimeException(message), "agent run")).toEither
      .flatMap(_.left.map(e => new RuntimeException(e.message)))
      .left
      .foreach(e =>
        AgentTracing.logger.warn(s"Tracing the failure of run ${handle.runId.value} failed: ${e.getMessage}")
      )

  private def trace(event: TraceEvent): Unit =
    Try(tracing.traceEvent(event)).toEither
      .flatMap(_.left.map(e => new RuntimeException(e.message)))
      .left
      .foreach { e =>
        AgentTracing.logger.warn(s"Tracing ${event.eventType} of run ${handle.runId.value} failed: ${e.getMessage}")
      }

  /** Waits up to [[AgentTracing.Drain]] for the run's last event to be traced, then detaches. */
  def detach(): Unit =
    if detached.compareAndSet(false, true) then
      CancelledError.catchInterrupt(Try(delivered.get(AgentTracing.Drain.toMillis, TimeUnit.MILLISECONDS))) match
        case Left(_) => Thread.currentThread().interrupt()
        case Right(Failure(_: TimeoutException)) =>
          AgentTracing.logger.warn(
            s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} did not deliver the run's last event within ${AgentTracing.Drain}; its trace may be incomplete"
          )
        case Right(_) => ()
      subscription.get.foreach(_.cancel())

private[agent] object AgentTracing:
  private val logger = LoggerFactory.getLogger(classOf[AgentTracing])

  /**
   * `record` as `TracingSubscriber` traces it, but an agent event - which the kernel's projection
   * names `graph.custom` - named by its own `agent.*` name; its data keeps `name`, `version` and
   * `payload`.
   */
  def toTrace(record: EventRecord): TraceEvent.CustomEvent =
    val traced = TracingSubscriber.toTrace(record)
    record.event match
      case RunEvent.Custom(name, _, _) if AgentEvents.durable(name) => traced.copy(name = name)
      case _                                                        => traced

  private def stateOf(result: RunResult[TurnOutput]): ThreadState = result match
    case RunResult.Completed(s, _, _) => s
    case s: RunResult.Suspended       => s.state
    case RunResult.Failed(s, _)       => s

  /**
   * The `AgentRunEnded` status of a run whose terminal event is `terminal` and whose turn ended with
   * `status` ([[AgentRun.status]]): `completed`, `step_limit_reached`, `suspended`,
   * `blocked:<guardrail>`, `cancelled`, `timed_out` or `failed` - a turn `await` refuses is `failed`.
   */
  def label(terminal: RunEvent, status: Result[AgentStatus]): String = status match
    case Right(_: AgentStatus.Completed)          => "completed"
    case Right(AgentStatus.StepLimitReached)      => "step_limit_reached"
    case Right(_: AgentStatus.Suspended)          => "suspended"
    case Right(AgentStatus.Blocked(guardrail, _)) => s"blocked:$guardrail"
    case Left(_) =>
      terminal match
        case RunEvent.RunCancelled => "cancelled"
        case RunEvent.RunTimedOut  => "timed_out"
        case _                     => "failed"

  /** How long `detach` waits, after the run ends, for its last event to be traced. */
  val Drain: FiniteDuration = 5.seconds
