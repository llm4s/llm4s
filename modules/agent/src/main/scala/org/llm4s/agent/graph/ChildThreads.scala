package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.middleware.GuardrailBlocked
import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError
import upickle.default.ReadWriter

import java.time.Instant

/**
 * Where a child thread came from (design 4.41): the thread whose run started it (`parent`), the tool call of that run
 * that started it (`call`, the call's [[IdempotencyKey]]), the agent it runs (`agent`), when it was first admitted
 * (`startedAt`, by the runtime's clock: [[GraphRuntime.startChild]] stamps it, whatever the caller passed), and how
 * deep it is: `1` for a child of a top-level thread, one more for each level below.
 *
 * A thread is a child thread exactly when its latest checkpoint records an origin ([[Checkpoint.origin]]), never
 * because of its id's shape. The origin is written with the child's first checkpoint and copied onto every later one,
 * as the tenant is. A child thread is driven only through its parent's run: `start`, `recover`, `resume`, `fork`,
 * `updateState` and `deleteThread` on it are refused with [[GraphError.ChildThread]].
 */
@Experimental
final case class RunOrigin private (
  parent: ThreadId,
  call: IdempotencyKey,
  agent: AgentId,
  startedAt: Instant,
  depth: Int
):
  def withParent(p: ThreadId): RunOrigin     = copy(parent = p)
  def withCall(c: IdempotencyKey): RunOrigin = copy(call = c)
  def withAgent(a: AgentId): RunOrigin       = copy(agent = a)
  def withStartedAt(at: Instant): RunOrigin  = copy(startedAt = at)
  def withDepth(d: Int): RunOrigin           = RunOrigin(parent, call, agent, startedAt, d)

object RunOrigin:

  /** Throws `IllegalArgumentException` for a `depth` below 1: a child is at least one level below its parent. */
  def apply(parent: ThreadId, call: IdempotencyKey, agent: AgentId, startedAt: Instant, depth: Int = 1): RunOrigin =
    require(depth >= 1, s"depth must be at least 1, was $depth")
    new RunOrigin(parent, call, agent, startedAt, depth)

  private given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)

  given ReadWriter[RunOrigin] = upickle.default.macroRW

/**
 * One direct child of a thread, as [[Checkpointer.children]] lists it: its id, its [[RunOrigin]], and its latest
 * checkpoint's status, id and creation time.
 */
@Experimental
final case class ChildThread(
  threadId: ThreadId,
  origin: RunOrigin,
  status: CheckpointStatus,
  latest: String,
  updatedAt: Instant
)

object ChildThread:
  private given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)

  given ReadWriter[ChildThread] = upickle.default.macroRW

  /** The child `checkpoint` describes, if it is a child thread's: `None` for a top-level thread's checkpoint. */
  def of(checkpoint: Checkpoint): Option[ChildThread] =
    checkpoint.origin.map(origin =>
      ChildThread(ThreadId(checkpoint.threadId), origin, checkpoint.status, checkpoint.id, checkpoint.createdAt)
    )

/**
 * How a run settled, recorded on the terminal (`Completed` or `Failed`) checkpoint that closed it
 * ([[Checkpoint.outcome]]), so a settled run's result can be rebuilt from the thread alone, after a restart as well
 * as live (design 4.41). A later run's first checkpoint carries none.
 *
 * The answer itself is in the thread's state, so `Answered` carries none. A graph that is not an agent turn records
 * `Answered` when it completes and `Failed` when a node blocks it; any graph records `Blocked` for a
 * `GuardrailBlocked` Block and `OutputInvalid` for a `GraphError.OutputInvalid` one.
 */
@Experimental
enum RunOutcome derives ReadWriter:

  /** The run completed with an answer, which is in the thread's state. */
  case Answered

  /** An agent turn reached its step limit (`AgentBuilder.withMaxSteps`). */
  case StepLimitReached

  /** An agent turn reached its run's token budget (`RunBudgets.maxTokens`). */
  case TokenLimitReached

  /** A guardrail blocked the run: its name and reason. */
  case Blocked(guardrail: String, reason: String)

  /**
   * The agent's typed answer failed its output schema twice (`GraphError.OutputInvalid`): every problem it was
   * reported with, which the error's message abbreviates.
   */
  case OutputInvalid(errors: Vector[String])

  /** The run settled as a failure: the error's kind (its class's simple name) and message. */
  case Failed(kind: String, message: String)

object RunOutcome:

  /** The outcome of a run a node blocked with `error` ([[NodeResult.Block]]). */
  private[agent] def blocked(error: LLMError): RunOutcome = error match
    case guardrail: GuardrailBlocked       => Blocked(guardrail.guardrail, guardrail.reason)
    case invalid: GraphError.OutputInvalid => OutputInvalid(invalid.errors)
    case other                             => failed(other)

  /** The outcome of a run that settled as the failure `error`. */
  private[agent] def failed(error: LLMError): RunOutcome = Failed(error.getClass.getSimpleName, error.message)
