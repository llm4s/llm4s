package org.llm4s.effect.cats

import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.StreamEvent

/** An item of an agent's event stream: each of the run's events, then its result. */
enum AgentStreamItem:
  case Event(event: StreamEvent)
  case Done(result: AgentResult)
