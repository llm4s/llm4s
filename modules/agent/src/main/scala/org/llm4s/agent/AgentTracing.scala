package org.llm4s.agent

import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.toolloop.{ LoopKeys, ToolLoop, TurnOutput }
import org.llm4s.types.Result
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.llm4s.trace.{ TraceContext, TraceEvent, TraceSpan, Tracing }
import org.slf4j.LoggerFactory

import java.time.Instant
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
 * [[org.llm4s.trace.TraceEvent.AgentRunEnded TraceEvent.AgentRunEnded]], its status derived by [[AgentRun.status]] as `await` derives it; a
 * failed run also traces `ErrorOccurred`. A run that ends without a terminal event (a crash, or a
 * failed terminal commit) traces no `AgentRunEnded`: once the subscription has delivered the run's
 * last event, at the run's end-of-run barrier, its error is traced as `ErrorOccurred` and logged at
 * WARN. Tracing failures are logged at WARN and never fail the run.
 *
 * `parent` is, for a delegated child's run, the parent thread and run whose `task` call drives it: its
 * `AgentRunEnded` names them (design 4.47).
 *
 * Every event it traces is placed in one trace (design 4.58, [[AgentTraceSpans]]): the run's span - its
 * `AgentRunEnded` - takes `continues` (the run's `RunConfig.traceContext`, or a child's `RunOrigin.trace`) as its
 * remote parent, or is the root of a trace of its own; each durable event, usage record and error is a span under it,
 * a tool call's `agent.tool_executed` spanning the call's duration with the span id its child's origin records (named
 * by the call's idempotency key).
 */
final private[agent] class AgentTracing(
  handle: RunHandle[TurnOutput],
  root: AgentId,
  loop: ToolLoop,
  tracing: Tracing,
  parent: Option[(ThreadId, RunId)] = None,
  continues: Option[TraceContext] = None
):
  // the run's own span, which every event of the run is placed under: named by the run's incarnation, whose claim's
  // token its first event - the claim - carries
  @volatile private var runSpan = AgentTraceSpans.run(handle.threadId, handle.runId, None, continues)
  private val incarnated        = new AtomicBoolean(false)
  // when the run started: its first event's time - its claim - else when tracing attached
  private val startedAt    = new AtomicReference[Option[Instant]](None)
  private val attachedAt   = Instant.now()
  private val delivered    = new CompletableFuture[Unit]()
  private val detached     = new AtomicBoolean(false)
  private val blockedBy    = new AtomicReference[Option[String]](None)
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val terminalSeen = new AtomicBoolean(false)
  private val disconnected = new AtomicBoolean(false)
  // this run's own usage, from its ModelCallCompleted events; the thread's LoopKeys.usage spans every run
  private val usage = new AtomicReference(UsageSummary())
  // the provider of the run's first model call that reported one: the run span's gen_ai.provider.name (#2275)
  private val provider = new AtomicReference[Option[String]](None)

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
    )

  private def onEvent(event: StreamEvent): Unit = event match
    case StreamEvent.Durable(record) =>
      startedAt.compareAndSet(None, Some(record.timestamp)): Unit
      if incarnated.compareAndSet(false, true) then
        runSpan = AgentTraceSpans.run(handle.threadId, handle.runId, Some(record.token), continues)
      trace(AgentTracing.toTrace(record).withSpan(placement(event, record)))
      event match
        case AgentEvents.ModelCallCompleted(m) =>
          if m.provider.isDefined then provider.compareAndSet(None, m.provider): Unit
          usage.updateAndGet(
            _.add(m.model, m.usage.map(_.toTokenUsage).getOrElse(TokenUsage(0, 0, 0)), m.estimatedCost)
          ): Unit
          m.usage.foreach(u =>
            trace(
              TraceEvent.TokenUsageRecorded(
                u.toTokenUsage,
                m.model,
                "agent_completion",
                record.timestamp,
                Some(under(AgentTraceSpans.usage(runSpan, record.threadId, record.seq), record.timestamp))
              )
            )
          )
        case AgentEvents.GuardrailBlocked(g) => blockedBy.set(Some(g.guardrail))
        // the calls of attempts that committed nothing are on the run's terminal event, not on ModelCallCompleted (§4.22)
        case StreamEvent.Durable(EventRecord(_, _, _, _, _, _, _, _, terminal)) =>
          AgentTracing.lostUsage(terminal).foreach(lost => usage.updateAndGet(_.merge(lost)): Unit)
        case _ => ()
      if RunScope.terminal(record.event) then
        terminalSeen.set(true)
        ended(record.event, record.timestamp)
    case StreamEvent.Disconnected(lastSeq, reason) =>
      disconnected.set(true)
      AgentTracing.logger.warn(
        s"Tracing of run ${handle.runId.value} on ${handle.threadId.value} ended after seq $lastSeq: $reason"
      )
    case _ => ()

  /** A span under the run's, started at `at`. */
  private def under(context: TraceContext, at: Instant): TraceSpan = TraceSpan(context, Some(runSpan), at)

  /**
   * Where `record` sits in the run's trace: a tool call spans its duration, ending at the record; any other event is a
   * point at its time. A call that ended is the call's one logical span, named by its idempotency key - a delegated
   * child's origin records the same one. A call that suspended (for approval, or with a question) is only a phase of
   * the call: a span of its own, named by the key and the record, so a call suspended in one run and resumed in another
   * is never one span id under two parents.
   */
  private def placement(event: StreamEvent, record: EventRecord): TraceSpan = event match
    case AgentEvents.ToolExecuted(t) =>
      val context = t.idempotencyKey.map(IdempotencyKey(_)) match
        case Some(key) if AgentTraceSpans.suspends(t.outcome) =>
          AgentTraceSpans.toolCallPhase(runSpan, key, record.seq)
        case Some(key) => AgentTraceSpans.toolCall(runSpan, key)
        case None      => AgentTraceSpans.toolCallWithoutKey(runSpan, handle.threadId, t.toolCallId, record.seq)
      under(context, record.timestamp.minusNanos(t.duration.toNanos))
    case _ => under(AgentTraceSpans.event(runSpan, record.threadId, record.seq), record.timestamp)

  /**
   * Traces the run's AgentRunEnded from its own result, which the run thread sets just after the
   * closing commit, with the status [[AgentRun.status]] derives - as `await` does - and the run's own
   * usage, summed from its `ModelCallCompleted` events (the thread's usage spans every run on it).
   */
  private def ended(terminal: RunEvent, at: Instant): Unit =
    val result = handle.await()
    val state  = result.toOption.map(AgentTracing.stateOf)
    val active = state.flatMap(_.get(LoopKeys.activeAgent).toOption.flatten).getOrElse(root)
    val status = result.flatMap(r => AgentRun.status(r, blockedBy.get.isDefined, loop))
    val label  = AgentTracing.label(terminal, status)
    val messages = status match
      case Right(_: AgentStatus.Blocked) | Left(_) => Vector.empty
      case Right(_) => state.flatMap(AgentRun.turnMessages(_).toOption).getOrElse(Vector.empty)
    status.left.foreach(error => if label == "failed" then traceError(error.message, at))
    trace(
      TraceEvent.AgentRunEnded(
        handle.threadId.value,
        handle.runId.value,
        active.value,
        label,
        messages,
        usage.get,
        // the run ends at its terminal event, not when tracing got to it
        at,
        parentThreadId = parent.map(_._1.value),
        parentRunId = parent.map(_._2.value),
        span = Some(TraceSpan(runSpan, continues, startedAt.get.getOrElse(attachedAt))),
        provider = provider.get
      )
    )

  /**
   * The scope's end. After the terminal event (traced by [[ended]]) or a `Disconnected` (logged) it
   * only releases `detach`. Otherwise the run ended without a terminal event - it crashed, or its
   * terminal commit failed: its end-of-run barrier ended the scope. Takes the run's error from its
   * result, which the run thread sets just after queuing that barrier, then warns and traces the
   * error as `ErrorOccurred`; it traces no `AgentRunEnded`.
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
      error.foreach(e => traceError(e.message, Instant.now()))
    delivered.complete(()): Unit

  /** Traces the run's error at `at`: its terminal event's time, or now for a run that ended without one. */
  private def traceError(message: String, at: Instant): Unit =
    val error = TraceEvent.ErrorOccurred(
      new RuntimeException(message),
      "agent run",
      at,
      Some(under(AgentTraceSpans.error(runSpan), at))
    )
    Try(tracing.traceEvent(error)).toEither
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
      case RunEvent.Custom(name, _, _) if AgentEvents.durable(name) || SubagentEvents.durable(name) =>
        traced.withName(name)
      case _ => traced

  private def stateOf(result: RunResult[TurnOutput]): ThreadState = result match
    case RunResult.Completed(s, _, _) => s
    case s: RunResult.Suspended       => s.state
    case RunResult.Failed(s, _)       => s

  /**
   * The `AgentRunEnded` status of a run whose terminal event is `terminal` and whose turn ended with
   * `status` ([[AgentRun.status]]): `completed`, `step_limit_reached`, `token_limit_reached`, `suspended`,
   * `blocked:<guardrail>`, `cancelled`, `timed_out` or `failed` - a turn `await` refuses is `failed`.
   */
  def label(terminal: RunEvent, status: Result[AgentStatus]): String = status match
    case Right(_: AgentStatus.Completed)          => "completed"
    case Right(AgentStatus.StepLimitReached)      => "step_limit_reached"
    case Right(AgentStatus.TokenLimitReached)     => "token_limit_reached"
    case Right(_: AgentStatus.Suspended)          => "suspended"
    case Right(AgentStatus.Blocked(guardrail, _)) => s"blocked:$guardrail"
    case Left(_) =>
      terminal match
        case RunEvent.RunCancelled(_, _) => "cancelled"
        case RunEvent.RunTimedOut(_, _)  => "timed_out"
        case _                           => "failed"

  /** The usage a terminal event carries for the attempts that committed nothing; `None` for any other event. */
  def lostUsage(event: RunEvent): Option[UsageSummary] = event match
    case RunEvent.RunFailed(_, usage, _) => usage
    case RunEvent.RunCancelled(usage, _) => usage
    case RunEvent.RunTimedOut(usage, _)  => usage
    case _                               => None

  /** How long `detach` waits, after the run ends, for its last event to be traced. */
  val Drain: FiniteDuration = 5.seconds
