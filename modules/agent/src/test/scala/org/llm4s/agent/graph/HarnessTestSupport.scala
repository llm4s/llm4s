package org.llm4s.agent.graph

import org.llm4s.types.Result

/** Thread state outside a runtime, for calling harness backends directly. */
object HarnessTestSupport {

  /** An empty state over `keys`. */
  def stateOf(keys: StateKey[?, ?]*): ThreadState = ThreadState.empty(keys.map(k => k.id -> k).toMap)

  /** `state` with `update` applied, as the runtime commits it. */
  def committed(state: ThreadState, update: StateUpdate): Result[ThreadState] = state.applyUpdate(update)
}
