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
final private[agent] case class PriorUsage(total: UsageSummary, foldedThrough: Long) derives ReadWriter:

  /** This with `lost`, from the terminal event numbered `seq`, folded in; unchanged for an event already folded. */
  def fold(seq: Long, lost: UsageSummary): PriorUsage =
    if seq <= foldedThrough then this else PriorUsage(total.merge(lost), seq)

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

  val counted: StateKey[Set[String], CountedUpdate] =
    StateKey[Set[String], CountedUpdate]("delegation-counted", Set.empty)((ids, update) =>
      Right(update match
        case CountedUpdate.Add(more) => ids ++ more
        case CountedUpdate.Reset     => Set.empty
      ): Result[Set[String]]
    )

/** An update to [[DelegationKeys.counted]]. */
private[agent] enum CountedUpdate derives ReadWriter:
  case Add(ids: Set[String])
  case Reset
