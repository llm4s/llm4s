package org.llm4s.agent.delegation

import org.llm4s.agent.graph.StateKey
import org.llm4s.types.Result
import org.llm4s.llmconnect.model.UsageSummary
import upickle.default.ReadWriter

/**
 * The model usage of a child thread's attempts that committed nothing, carried forward on the thread (design 4.42):
 * `total` is the usage folded so far, from the terminal events (`RunFailed`, `RunCancelled`, `RunTimedOut`) of
 * attempts a later `recoverChild` or `settleChild` continued, and `foldedThrough` the sequence number of the last
 * terminal event folded. A terminal event at or below `foldedThrough` is already in `total`.
 */
final private[agent] case class PriorUsage(total: UsageSummary, foldedThrough: Long, failedSteps: Long = 0L)
    derives ReadWriter:

  /**
   * This with `lost` and `steps` - the usage and the agent model steps on the terminal event numbered `seq` - folded
   * in; unchanged for an event already folded. `failedSteps` counts only the agent's own model steps, never a
   * middleware's model calls, which `total` holds too (design 4.45).
   */
  def fold(seq: Long, lost: UsageSummary, steps: Long = 0L): PriorUsage =
    if seq <= foldedThrough then this else PriorUsage(total.merge(lost), seq, failedSteps + steps)

private[agent] object PriorUsage:
  val empty: PriorUsage = PriorUsage(UsageSummary(), 0L)

/** State keys the runtime owns on child threads: no tool or middleware may declare them (design 4.42). */
private[agent] object DelegationKeys:

  /**
   * The lost usage of a child thread's earlier attempts ([[PriorUsage]]). Every agent graph registers it, so a child
   * thread's state can hold it; only the child runner's admission and settlement write it, and an update replaces it.
   */
  val priorUsage: StateKey[PriorUsage, PriorUsage] =
    StateKey.replace[PriorUsage]("delegation-prior-usage", PriorUsage.empty)

  /**
   * The child threads whose roll-up this turn's committed results already counted (design 4.42), on the parent's
   * thread: a call that suspended after its child ran committed the roll-up with the suspension, and when it runs again
   * the reattached child is not counted twice. Written only with a tool call's result, and cleared as each turn starts.
   */
  /**
   * The digest of the input a child thread was started with (design 4.42), written with its first checkpoint: a call
   * that runs again with other arguments (an approval that edited them) derives the same child id, and is refused
   * rather than given the report of the input it no longer asks about.
   */
  val input: StateKey[Option[String], Option[String]] =
    StateKey.replace[Option[String]]("delegation-input", None)

  /**
   * A child thread's whole-life limits (design 4.45, [[ChildGrant]]), written by the child runner with its first
   * admission and narrowed, never widened, by each later one. The tool loop ends the child's turn `StepLimitReached`
   * at the lower of its `steps` and the agent's own `maxSteps`. `None` on a top-level thread.
   */
  val grant: StateKey[Option[ChildGrant], Option[ChildGrant]] =
    StateKey.replace[Option[ChildGrant]]("delegation-grant", None)

  val counted: StateKey[Set[String], CountedUpdate] =
    StateKey[Set[String], CountedUpdate]("delegation-counted", Set.empty)((ids, update) =>
      Right(update match
        case CountedUpdate.Add(more) => ids ++ more
        case CountedUpdate.Reset     => Set.empty
      ): Result[Set[String]]
    )

/**
 * A child's limits for its whole life (design 4.45), each `None` when unlimited: the cap on its turn's steps, its
 * input and output tokens, its deadline (chosen at its first admission: its start plus the granted timeout, or the
 * parent's deadline then if sooner, never moved by a later parent run), and its run's `maxConcurrency`. `lostSteps` is
 * the model calls of its failed attempts, which never committed their steps but count against `steps`.
 */
final private[agent] case class ChildGrant(
  steps: Option[Int],
  tokens: Option[Long],
  deadline: Option[java.time.Instant],
  concurrency: Option[Int] = None,
  lostSteps: Long = 0L
):

  /** The steps the child's turn may still count, its failed attempts' model calls deducted; `None` when unlimited. */
  def stepCap: Option[Int] = steps.map(s => (s - lostSteps).max(0L).toInt)

  /** Each limit the lower of this one's and `other`'s: a later admission can narrow a grant, never widen it. */
  def narrowed(other: ChildGrant): ChildGrant =
    def lower[A](a: Option[A], b: Option[A])(using ord: Ordering[A]): Option[A] =
      (a ++ b).reduceOption(ord.min)
    ChildGrant(
      lower(steps, other.steps),
      lower(tokens, other.tokens),
      lower(deadline, other.deadline),
      lower(concurrency, other.concurrency),
      lostSteps.max(other.lostSteps)
    )

private[agent] object ChildGrant:
  private given ReadWriter[java.time.Instant] =
    upickle.default.readwriter[String].bimap(_.toString, java.time.Instant.parse)
  given ReadWriter[ChildGrant] = upickle.default.macroRW

/** An update to [[DelegationKeys.counted]]. */
private[agent] enum CountedUpdate derives ReadWriter:
  case Add(ids: Set[String])
  case Reset
