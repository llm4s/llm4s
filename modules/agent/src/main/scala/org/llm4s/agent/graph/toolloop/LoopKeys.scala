package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.StateKey
import org.llm4s.llmconnect.model.UsageSummary

/** State keys the tool loop owns: no tool or middleware may declare them. */
object LoopKeys:

  /** Usage accumulated over the thread, per model. An update is one call's summary, merged into the total. */
  val usage: StateKey[UsageSummary, UsageSummary] =
    StateKey[UsageSummary, UsageSummary]("usage", UsageSummary())((total, call) => Right(total.merge(call)))

  /** The agent the thread is with: unset on a new thread, which starts with the root. An update replaces it. */
  val activeAgent: StateKey[Option[AgentId], AgentId] =
    StateKey[Option[AgentId], AgentId]("active-agent", None)((_, next) => Right(Some(next)))

  /** The current turn's step count and outcome; `input` resets it. */
  val turn: StateKey[TurnState, TurnState] = StateKey.replace[TurnState]("turn", TurnState(0, None))

  /** The thread's last handoff, if any; each transfer replaces it. */
  val transfer: StateKey[Option[Transfer], Transfer] =
    StateKey[Option[Transfer], Transfer]("transfer", None)((_, next) => Right(Some(next)))
