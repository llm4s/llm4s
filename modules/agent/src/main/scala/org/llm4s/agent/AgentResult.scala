package org.llm4s.agent

import org.llm4s.agent.graph.{ InterruptId, RunId, ThreadId }
import org.llm4s.agent.graph.toolloop.{ ApprovalDecision, ApprovalRequest, ToolQuestionRequest }
import org.llm4s.llmconnect.model.{ Message, UsageSummary }
import upickle.default.ReadWriter

/** How an agent's turn ended, or why it is waiting. */
enum AgentStatus:

  /** The active agent gave a final answer; `answer` is its content. */
  case Completed(answer: String)

  /**
   * A guardrail refused the turn: `guardrail` is the first failing guardrail's name, `reason` every
   * failure's error. An input block stores nothing of the turn; an output block removes it - the
   * query, any tool calls and the answer - so `messages` is the history from before the turn. Usage
   * keeps the turn's model calls. The thread is usable, and the next turn runs normally.
   */
  case Blocked(guardrail: String, reason: String)

  /** The turn used the active agent's `maxSteps` model calls without a final answer. */
  case StepLimitReached

  /**
   * The turn is parked on tool approvals and tool questions. Answer them with
   * [[AgentResult.approve]], [[AgentResult.reject]], [[AgentResult.edit]] and [[AgentResult.reply]],
   * and continue with [[Agent.resume]].
   */
  case Suspended(
    approvals: Vector[(InterruptId, ApprovalRequest)],
    questions: Vector[(InterruptId, ToolQuestionRequest)]
  )

/**
 * The outcome of one agent run: where it ran, how it ended, and the thread's state at its end. A
 * readable value, built only by [[Agent]]; a conversation continues by its `threadId`.
 *
 * @param threadId the conversation's thread
 * @param runId this run
 * @param activeAgent the agent the thread is with at the run's end: the next turn starts with it
 * @param status how the turn ended, or what it waits for
 * @param messages the thread's full history, without system prompts
 * @param usage token usage accumulated over the thread, per model
 */
final case class AgentResult private (
  threadId: ThreadId,
  runId: RunId,
  activeAgent: AgentId,
  status: AgentStatus,
  messages: Vector[Message],
  usage: UsageSummary
):

  /** The final answer, when the turn `Completed`. */
  def answer: Option[String] = status match
    case AgentStatus.Completed(text) => Some(text)
    case _                           => None

  /** Approves the pending tool call `id`, for [[Agent.resume]]. */
  def approve(id: InterruptId): (InterruptId, ujson.Value) = decide(id, ApprovalDecision.Approve)

  /** Rejects the pending tool call `id`: the model sees `Rejected: <reason>` as its result. */
  def reject(id: InterruptId, reason: String): (InterruptId, ujson.Value) = decide(id, ApprovalDecision.Reject(reason))

  /** Approves the pending tool call `id` with new `arguments`, which replace the call's in the history. */
  def edit(id: InterruptId, arguments: ujson.Value): (InterruptId, ujson.Value) =
    decide(id, ApprovalDecision.Edit(arguments))

  /** Answers the tool question `id` with `value`, of the asking tool's answer type. */
  def reply[Ans: ReadWriter](id: InterruptId, value: Ans): (InterruptId, ujson.Value) =
    id -> upickle.default.writeJs(value)

  private def decide(id: InterruptId, decision: ApprovalDecision): (InterruptId, ujson.Value) =
    id -> upickle.default.writeJs(decision)

object AgentResult:
  private[agent] def apply(
    threadId: ThreadId,
    runId: RunId,
    activeAgent: AgentId,
    status: AgentStatus,
    messages: Vector[Message],
    usage: UsageSummary
  ): AgentResult = new AgentResult(threadId, runId, activeAgent, status, messages, usage)
