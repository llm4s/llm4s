package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.{ EffectKey, ReplayGuard, RunContext }
import org.llm4s.agent.memory.{ AppliedWrite, AppliedWriteKey, Memory, MemoryBounds, MemoryId }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * The slice's one kind of external effect: a write to a tenant's store, guarded by C4 (design §4.31). Recording and
 * each consolidation group call [[write]] with their own key.
 */
private[memory] object MemoryEffects:

  /** C4's key as the store's: its scope and value. */
  def keyOf(key: EffectKey): AppliedWriteKey = AppliedWriteKey(key.scope, key.value)

  /** The guard stores and replays the outcome with [[AppliedWrite]]'s own versioned JSON. */
  given ReadWriter[AppliedWrite] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[AppliedWrite](
        AppliedWrite.toJson,
        json => AppliedWrite.fromJson(json).fold(e => throw new IllegalArgumentException(e.message), identity)
      )

  /**
   * Performs the write `plan` names - memories to add, ids to remove - on `cell` under `key`.
   *
   *  - A transactional store: `ReplayGuard.transactional`. The effect first reads the store's applied write for the
   *    key, fenced by the run's token: a key already applied returns its recorded outcome and `plan` never runs.
   *    Otherwise `plan` runs, and `applyAtomically` writes, evicts and records the outcome in one transaction.
   *  - Any other store: `ReplayGuard.unverifiable`, not idempotent, so a write that began and never recorded its
   *    outcome is refused on replay rather than repeated.
   */
  def write(context: RunContext, cell: TenantMemory, key: EffectKey, bounds: MemoryBounds)(
    plan: => Result[(Seq[Memory], Seq[MemoryId])]
  ): Result[AppliedWrite] =
    if cell.transactional then
      val stored = keyOf(key)
      ReplayGuard.transactional[AppliedWrite](context, key, cell) { token =>
        cell.write(_.appliedWrite(stored, token.value)).flatMap {
          case Some(recorded) => Right(recorded)
          case None =>
            plan.flatMap((add, remove) => cell.write(_.applyAtomically(stored, token.value, add, remove, bounds)))
        }
      }
    else
      ReplayGuard.unverifiable[AppliedWrite](context, key, idempotent = false) {
        plan.flatMap((add, remove) => cell.writePlain(add, remove, bounds))
      }
