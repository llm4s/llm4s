package org.llm4s.agent.graph

/** Keys for writes the contracts' subjects make as "another writer", outside any run and any guard. */
object ReplayKeys {
  def unguarded(seed: String): EffectKey =
    EffectKey.derive(ThreadId("another-writer"), IdempotencyKey(seed), "seed", Nil)
}
