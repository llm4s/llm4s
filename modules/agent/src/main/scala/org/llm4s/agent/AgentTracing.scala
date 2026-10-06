package org.llm4s.agent

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ LoopKeys, TurnOutcome, TurnOutput }
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.model.UsageSummary
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
 * usage as `TokenUsageRecorded(usage, model, "agent_completion")`. On the run's terminal event it
 * takes the run's result from its handle (set just after the closing commit) and traces one
 * [[TraceEvent.AgentRunEnded]]; a failed run also traces `ErrorOccurred`. A run that ends without a
 * terminal event (a crash, or a failed terminal commit) traces no `AgentRunEnded`: its scope ends
 * once the subscription has delivered what it had, which ends the tracing. Tracing failures are
 * logged at WARN and never fail the run.
 */
final private[agent] class AgentTracing(handle: RunHandle[TurnOutput], root: AgentId, tracing: Tracing):
  private val delivered    = new CompletableFuture[Unit]()
  private val detached     = new AtomicBoolean(false)
  private val blockedBy    = new AtomicReference[Option[String]](None)
  private val subscription = new AtomicReference[Option[Subscription]](None)

  // ends - once - after the terminal event is traced, after a Disconnected, or when the run lacks a terminal event
  private val scope = RunScope(handle.runId, onEvent, () => delivered.complete(()): Unit)

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
          m.usage.foreach(u => trace(TraceEvent.TokenUsageRecorded(u.toTokenUsage, m.model, "agent_completion")))
        case AgentEvents.GuardrailBlocked(g) => blockedBy.set(Some(g.guardrail))
        case _                               => ()
      if RunScope.terminal(record.event) then ended(record.event)
    case StreamEvent.Disconnected(lastSeq, reason) =>
      AgentTracing.logger.warn(
        s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} ended after seq $lastSeq: $reason"
      )
    case _ => ()

  /** Traces the run's AgentRunEnded from its own result, which the run thread sets just after the closing commit. */
  private def ended(terminal: RunEvent): Unit =
    val result = handle.await()
    val state = result.toOption.map {
      case RunResult.Completed(s, _, _) => s
      case s: RunResult.Suspended       => s.state
      case RunResult.Failed(s, _)       => s
    }
    val usage  = state.flatMap(_.get(LoopKeys.usage).toOption).getOrElse(UsageSummary())
    val active = state.flatMap(_.get(LoopKeys.activeAgent).toOption.flatten).getOrElse(root)
    val (status, messages) = (terminal, result) match
      case (RunEvent.RunCompleted, Right(RunResult.Completed(s, out, _))) =>
        val status = out.outcome match
          case TurnOutcome.Completed        => "completed"
          case TurnOutcome.StepLimitReached => "step_limit_reached"
        (status, AgentRun.turnMessages(s).getOrElse(Vector.empty))
      case (_: RunEvent.RunSuspended, Right(s: RunResult.Suspended)) =>
        ("suspended", AgentRun.turnMessages(s.state).getOrElse(Vector.empty))
      case (RunEvent.RunCancelled, _) => ("cancelled", Vector.empty)
      case (RunEvent.RunTimedOut, _)  => ("timed_out", Vector.empty)
      case (_: RunEvent.RunFailed, _) =>
        blockedBy.get match
          case Some(guardrail) => (s"blocked:$guardrail", Vector.empty)
          case None =>
            result match
              case Right(RunResult.Failed(_, error)) => traceError(error.message)
              case Left(error)                       => traceError(error.message)
              case _                                 => ()
            ("failed", Vector.empty)
      case _ => ("failed", Vector.empty)
    trace(TraceEvent.AgentRunEnded(handle.threadId.value, handle.runId.value, active.value, status, messages, usage))

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

  /** How long `detach` waits, after the run ends, for its last event to be traced. */
  val Drain: FiniteDuration = 5.seconds
