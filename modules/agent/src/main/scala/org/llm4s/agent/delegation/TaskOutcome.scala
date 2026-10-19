package org.llm4s.agent.delegation

import org.llm4s.agent.{ AgentId, AgentStatus }
import org.llm4s.agent.graph.{ RunId, ThreadId, ToolCallId }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.UsageSummary
import upickle.default.ReadWriter

/**
 * The input of a subagent defined with [[TypedAgent.text]]: a self-contained description of the task, which becomes
 * the child's first user message as it is. Every such subagent is offered through the one `task` tool (design 4.44).
 */
@Experimental
final case class TaskRequest(description: String) derives ReadWriter

/**
 * What a parent does when a child run fails (design 4.44): a provider error, a tool's `Fatal`, or a refusal - the
 * child was blocked, reached its step limit, or its typed answer failed its schema.
 */
@Experimental
enum ChildFailure:

  /**
   * The default: the parent model sees the failure as the `task` call's tool error, naming the subagent, the child
   * thread and the reason, and can compose without the child. The child thread stays for inspection.
   */
  case Report

  /**
   * The parent run fails with the child's error (`GraphError.ToolFailed`); the parent's `recover` runs the call again,
   * which continues the child from its own checkpoint rather than starting it again.
   */
  case Fail

/**
 * The child run a delegated task ran as (design 4.44): its thread, the run that produced the outcome, the subagent's
 * id, the parent's tool call that started it, and its depth - 1 for a child of a top-level thread. A child run with no
 * parent ([[BoundAgent.run]]) has depth 0 and an empty `toolCallId`.
 */
@Experimental
final case class ChildRef(threadId: ThreadId, runId: RunId, agent: AgentId, toolCallId: ToolCallId, depth: Int)

object ChildRef:
  private given ReadWriter[RunId]      = upickle.default.readwriter[String].bimap(_.value, RunId(_))
  private given ReadWriter[ToolCallId] = upickle.default.readwriter[String].bimap(_.value, ToolCallId(_))

  given ReadWriter[ChildRef] = upickle.default.macroRW

/**
 * How a delegated task ended (design 4.44): only typed data crosses from the child to the parent, never the child's
 * messages.
 */
@Experimental
enum TaskOutcome[+O]:

  /** The child answered: `report` is its answer, decoded as `O`; `usage` is the child thread's. */
  case Reported(report: O, child: ChildRef, usage: UsageSummary)

  /** The child ended without a report it may give: why, and the child thread's usage. */
  case Refused(child: ChildRef, reason: TaskRefusal, usage: UsageSummary)

  /**
   * The child is waiting for approvals or answers, `status`. Through a `task` call, the parent suspends too, listing
   * the child's interrupts in its own `AgentStatus.Suspended.delegated`, and resuming the parent resumes the child
   * (design 4.46).
   */
  case Suspended(child: ChildRef, status: AgentStatus.Suspended)

  /**
   * The task was refused before any child existed, so there is no child thread and no `ChildRef`: `reason` says why
   * (an exhausted step or token budget, design 4.45). A replay decides the same from the same snapshot.
   */
  case NotStarted(agent: AgentId, reason: TaskRefusal)

/** Why a child gave no report (design 4.44). Each case is rebuilt from one `RunOutcome` on replay. */
@Experimental
enum TaskRefusal derives ReadWriter:

  /** A guardrail of the child blocked its turn. */
  case Blocked(guardrail: String, reason: String)

  /** The child used its `maxSteps` model calls without an answer. */
  case StepLimitReached

  /** The child used its token budget without an answer. */
  case TokenLimitReached

  /** The child's typed answer failed its output schema twice: every problem it was reported with. */
  case OutputInvalid(errors: Vector[String])

  /** The child run failed: its error's message. */
  case Failed(message: String)

  /** A one-line description, for the parent model's tool error. */
  def describe: String = this match
    case Blocked(guardrail, reason) => s"blocked by guardrail '$guardrail': $reason"
    case StepLimitReached           => "reached its step limit without an answer"
    case TokenLimitReached          => "reached its token budget without an answer"
    case OutputInvalid(errors)      => s"its answer did not match its output schema: ${errors.mkString("; ")}"
    case Failed(message)            => s"failed: $message"

object TaskRefusal:
  /**
   * The status label `SubagentEvents.TaskEnded` gives `reason` (design 4.47): `blocked:<guardrail>`,
   * `step_limit_reached`, `token_limit_reached`, `output_invalid` or `failed` - no reason text, which may hold content.
   */
  private[agent] def label(reason: TaskRefusal): String = reason match
    case Blocked(guardrail, _) => s"blocked:$guardrail"
    case StepLimitReached      => "step_limit_reached"
    case TokenLimitReached     => "token_limit_reached"
    case OutputInvalid(_)      => "output_invalid"
    case Failed(_)             => "failed"
