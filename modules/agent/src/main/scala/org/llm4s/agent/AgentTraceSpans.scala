package org.llm4s.agent

import org.llm4s.agent.events.ToolExecutionOutcome
import org.llm4s.agent.graph.{ FencingToken, IdempotencyKey, RunId, ThreadId }
import org.llm4s.trace.TraceContext

/**
 * The spans of an agent's runs (design 4.58), named so that every party computes the same ids with no coordination: a
 * run's tracing, which places the run's events, and a delegating `task` call, which records in its child's origin the
 * span of the tool call that started it - possibly on another machine, or in a later run of the parent.
 *
 *  - A run's span is derived from its incarnation - its thread, [[RunId]] and claim's fencing token, since a `RunId`
 *    may be reused (a recovery under the same id, a reused `RunConfig`) but a token never is - under the context it
 *    continues (`RunConfig.traceContext`, a child's `RunOrigin.trace`) when it has one, else as the root of a trace of
 *    its own.
 *  - A tool call's span is derived from the trace and the call's [[org.llm4s.agent.graph.IdempotencyKey]] - not from
 *    the run, nor from the provider's call id, which a later model request may reuse - so a parent run recovered under
 *    a new `RunId`, in the same trace, names the same span for the same call, a call of another request another span,
 *    and the child a call started stays under it. That span is the call's one logical span, placed by the event of the
 *    phase that ended the call; a phase that suspended it (`NeedsApproval`, `Asked`) is a span of its own, from the key
 *    and its event's sequence number, so no span id is placed twice. An event stored before the key was recorded
 *    falls back to its thread and sequence number.
 *  - Each other durable event's span is derived from its thread and sequence number, unique on the thread.
 */
private[agent] object AgentTraceSpans:

  /**
   * A span's key: its kind, then each part length-prefixed (`<length>:<part>`), so the encoding is injective whatever
   * the parts hold - caller-chosen thread and run ids may contain any character, `/` included.
   */
  private[agent] def named(kind: String, parts: String*): String =
    parts.map(part => s"${part.length}:$part").mkString(s"$kind/", "", "")

  def run(
    threadId: ThreadId,
    runId: RunId,
    token: Option[FencingToken],
    continues: Option[TraceContext]
  ): TraceContext =
    val key = named("llm4s.run", Seq(threadId.value, runId.value) ++ token.map(_.value.toString)*)
    continues.fold(TraceContext.rootFor(key))(_.spanFor(key))

  def toolCall(run: TraceContext, call: IdempotencyKey): TraceContext =
    run.spanFor(named("llm4s.tool", call.value))

  /** A phase of a tool call that suspended it, announced by event `seq`: a span of its own, never the call's. */
  def toolCallPhase(run: TraceContext, call: IdempotencyKey, seq: Long): TraceContext =
    run.spanFor(named("llm4s.tool-phase", call.value, seq.toString))

  /** A tool call's span from an event stored before it recorded its key: unique to the event. */
  def toolCallWithoutKey(run: TraceContext, threadId: ThreadId, toolCallId: String, seq: Long): TraceContext =
    run.spanFor(named("llm4s.tool-unkeyed", threadId.value, toolCallId, seq.toString))

  /** Whether a tool call that ended `outcome` only suspended: the call goes on in a later run. */
  def suspends(outcome: ToolExecutionOutcome): Boolean = outcome match
    case ToolExecutionOutcome.NeedsApproval | ToolExecutionOutcome.Asked => true
    case _                                                               => false

  def event(run: TraceContext, threadId: String, seq: Long): TraceContext =
    run.spanFor(named("llm4s.event", threadId, seq.toString))

  def usage(run: TraceContext, threadId: String, seq: Long): TraceContext =
    run.spanFor(named("llm4s.usage", threadId, seq.toString))

  /** The run's error: named by the run's own span, which is unique to the incarnation. */
  def error(run: TraceContext): TraceContext =
    run.spanFor(named("llm4s.error", run.spanId))
