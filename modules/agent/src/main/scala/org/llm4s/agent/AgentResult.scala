package org.llm4s.agent

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.agent.delegation.DelegatedRequest
import org.llm4s.agent.graph.{ Breakpoint, InterruptId, RunId, ThreadId }
import org.llm4s.agent.graph.toolloop.{
  ApprovalDecision,
  ApprovalRequest,
  BreakpointRequest,
  MiddlewareQuestionRequest,
  OutputFormat,
  ToolQuestionRequest
}
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ Message, UsageSummary }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * How an agent's turn ended, or why it is waiting.
 *
 * `Completed`, `Truncated` and `Blocked` are closed: each is exactly the fields it has, and anything more about the
 * turn goes on [[AgentResult]]. `Suspended` holds a [[Suspension]], which can gain a kind of pending interrupt
 * without breaking a caller.
 */
@Stable
enum AgentStatus:

  /** The active agent gave a final answer; `answer` is its content. */
  case Completed(answer: String)

  /**
   * The active agent's final answer was cut off: the model stopped at its output limit (`FinishReason.Length`,
   * `CompletionOptions.maxTokens` or the model's own cap) before it finished. `partialAnswer` is what it wrote, stored
   * in the thread as any answer is; it is not an [[AgentResult.answer]], since it is incomplete. Like
   * `StepLimitReached`, a completed turn: the next turn runs normally, so a caller can ask the agent to go on, or
   * run again with a higher limit (RFC 0009, open question 6).
   */
  case Truncated(partialAnswer: String)

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
   * The run used its token budget (`RunBudgets.maxTokens`) without a final answer: at the budget the turn
   * ended before the next model call. The call in flight when the budget was crossed was not interrupted,
   * so `usage` can exceed the budget by one call. Like `StepLimitReached`, a completed turn: the next
   * turn, with its own budget, runs normally.
   */
  case TokenLimitReached

  /**
   * The turn is parked on the interrupts `suspension` lists: tool approvals, tool questions, middleware questions,
   * static breakpoints and the interrupts of suspended subagents, each keyed by its interrupt id. Answer them - any
   * non-empty subset at a time - with [[AgentResult.approve]], [[AgentResult.reject]] and [[AgentResult.edit]]
   * (approvals), [[AgentResult.reply]] (a tool's or a middleware's question, with its answer type) and
   * [[AgentResult.proceed]] (breakpoints), and continue with [[Agent.resume]]; unanswered ones stay pending.
   */
  @Experimental case Suspended(suspension: Suspension)

/**
 * What a suspended turn waits on ([[AgentStatus.Suspended]]), each interrupt keyed by its id.
 *
 * `delegated` lists the interrupts of suspended subagents (design 4.46), flattened: each child interrupt under the
 * composite id `<parent interrupt>~<child interrupt>` (a grandchild's `p~c~g`), with the child run that waits. The
 * [[AgentResult]] helpers answer them by that id, encoding the answer the child expects, and [[Agent.resume]] routes
 * each to its child through the parent. A `task` call waiting on its child is listed only here, never in `questions`.
 *
 * @param approvals tool calls waiting for approval
 * @param questions questions tools asked
 * @param middlewareQuestions questions middleware asked
 * @param breakpoints static breakpoints holding a task
 * @param delegated interrupts of suspended subagents
 */
@Experimental
final case class Suspension private (
  approvals: Vector[(InterruptId, ApprovalRequest)],
  questions: Vector[(InterruptId, ToolQuestionRequest)],
  middlewareQuestions: Vector[(InterruptId, MiddlewareQuestionRequest)],
  breakpoints: Vector[(InterruptId, BreakpointRequest)],
  delegated: Vector[(InterruptId, DelegatedRequest)]
):
  def withApprovals(value: Vector[(InterruptId, ApprovalRequest)]): Suspension     = copy(approvals = value)
  def withQuestions(value: Vector[(InterruptId, ToolQuestionRequest)]): Suspension = copy(questions = value)
  def withMiddlewareQuestions(value: Vector[(InterruptId, MiddlewareQuestionRequest)]): Suspension =
    copy(middlewareQuestions = value)
  def withBreakpoints(value: Vector[(InterruptId, BreakpointRequest)]): Suspension = copy(breakpoints = value)
  def withDelegated(value: Vector[(InterruptId, DelegatedRequest)]): Suspension    = copy(delegated = value)

@Experimental
object Suspension:

  def apply(
    approvals: Vector[(InterruptId, ApprovalRequest)] = Vector.empty,
    questions: Vector[(InterruptId, ToolQuestionRequest)] = Vector.empty,
    middlewareQuestions: Vector[(InterruptId, MiddlewareQuestionRequest)] = Vector.empty,
    breakpoints: Vector[(InterruptId, BreakpointRequest)] = Vector.empty,
    delegated: Vector[(InterruptId, DelegatedRequest)] = Vector.empty
  ): Suspension =
    new Suspension(approvals, questions, middlewareQuestions, breakpoints, delegated)

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
@Stable
final case class AgentResult private (
  threadId: ThreadId,
  runId: RunId,
  activeAgent: AgentId,
  status: AgentStatus,
  messages: Vector[Message],
  usage: UsageSummary
):

  /**
   * The final answer, when the turn `Completed`. A turn whose answer was cut off at the model's output limit has
   * none: its partial answer is on [[AgentStatus.Truncated]] (and in `messages`), never here.
   */
  def answer: Option[String] = status match
    case AgentStatus.Completed(text) => Some(text)
    case _                           => None

  /**
   * The answer read as `O`: the agent's response format ([[AgentBuilder.withOutput]]) makes it JSON of `O`'s
   * schema, and any JSON answer - fenced or in prose - reads too. `Left(ValidationError)` when the turn did not
   * complete, or the answer is not JSON, is JSON `null`, or does not read as `O`.
   */
  def decodeOutput[O: ReadWriter]: Result[O] =
    answer
      .toRight(ValidationError("output", s"the turn has no answer: it ended ${status.productPrefix}"))
      .flatMap(OutputFormat.decode[O])

  /** Approves the pending tool call `id`, for [[Agent.resume]]. */
  @Experimental
  def approve(id: InterruptId): (InterruptId, ujson.Value) = decide(id, ApprovalDecision.Approve)

  /** Rejects the pending tool call `id`: the model sees `Rejected: <reason>` as its result. */
  @Experimental
  def reject(id: InterruptId, reason: String): (InterruptId, ujson.Value) = decide(id, ApprovalDecision.Reject(reason))

  /** Approves the pending tool call `id` with new `arguments`, which replace the call's in the history. */
  @Experimental
  def edit(id: InterruptId, arguments: ujson.Value): (InterruptId, ujson.Value) =
    decide(id, ApprovalDecision.Edit(arguments))

  /** Answers the question `id` - a tool's or a middleware's - with `value`, of the asker's answer type. */
  @Experimental
  def reply[Ans: ReadWriter](id: InterruptId, value: Ans): (InterruptId, ujson.Value) =
    id -> upickle.default.writeJs(value)

  /** Continues the task the breakpoint `id` holds. */
  @Experimental
  def proceed(id: InterruptId): (InterruptId, ujson.Value) = id -> Breakpoint.proceed

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
