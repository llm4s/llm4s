package org.llm4s.agent.events

import org.llm4s.agent.delegation.ChildRef
import org.llm4s.agent.graph.EventType
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.UsageSummary
import org.llm4s.util.DurationJson
import upickle.default.{ macroRW, ReadWriter }

import scala.concurrent.duration.FiniteDuration

/**
 * A delegated task's child run was admitted (design 4.47): live, sent for the parent's `task` call as the child's run
 * claims its thread - only for a run that was admitted - and again on each reattach. It is the nested stream's handle: a parent
 * subscription made with `nested = true` receives the child's events after it, as `StreamEvent.Nested(child, _)`.
 * `child.runId` is the run the call is about to drive, or, for a child already settled, the run that settled it.
 */
@Experimental
final case class TaskStarted(child: ChildRef) derives ReadWriter

/**
 * A delegated task ended (design 4.47): durable, committed with the `task` call's terminal result - a report, or a
 * refusal the parent model sees as a tool error - so once per child. A call that fails its parent (under
 * `ChildFailure.Fail`) commits nothing, and its replay sends the one `TaskEnded`.
 *
 * `status` is `completed`, `blocked:<guardrail>`, `step_limit_reached`, `token_limit_reached`, `output_invalid` or
 * `failed`; a call whose child suspended commits none until a resumed call settles it. `usage` is the child's whole
 * life's model usage, every attempt and phase, the roll-up added to the parent. `duration` runs from the child's first admission (`RunOrigin.startedAt`) to the call's end, by the
 * runtime's clock; it is encoded in milliseconds. No request, report or reason: the child's own log and the parent's
 * tool message hold those (rule 9). It is the source of per-subagent count, latency, outcome and token metrics.
 */
@Experimental
final case class TaskEnded(child: ChildRef, status: String, usage: UsageSummary, duration: FiniteDuration)

object TaskEnded:
  given ReadWriter[TaskEnded] =
    import DurationJson.millisRW
    macroRW

/**
 * The events a delegating agent's runs send for its children (design 4.47), each an
 * [[org.llm4s.agent.graph.EventType]]:
 *
 * {{{
 * agent.stream(threadId, "Research these three topics", nested = true)(
 *   EventHandlers
 *     .on(SubagentEvents.TaskStarted)(s => println(s"started ${s.child.agent}"))
 *     .onChild(AgentEvents.TextDelta)((child, d) => print(s"[${child.agent}] ${d.text}"))
 *     .on(SubagentEvents.TaskEnded)(e => println(s"${e.child.agent}: ${e.status} in ${e.duration}"))
 * )
 * }}}
 *
 * Durable (stored, replayed, no content): [[TaskEnded]]. Live: [[TaskStarted]].
 */
@Experimental
object SubagentEvents:
  val TaskStarted: EventType[org.llm4s.agent.events.TaskStarted] = EventType("agent.task_started", 1)
  val TaskEnded: EventType[org.llm4s.agent.events.TaskEnded]     = EventType("agent.task_ended", 1)

  /** The names of the durable subagent events. */
  val durable: Set[String] = Set(TaskEnded.name)
