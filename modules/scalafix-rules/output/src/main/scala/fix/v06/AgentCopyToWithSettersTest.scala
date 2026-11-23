package fix.v06

import org.llm4s.agent.{ ContextWindowConfig, Handoff }
import org.llm4s.agent.graph.toolloop.AgentInput

object AgentCopyToWithSettersTest {
  def tighter(config: ContextWindowConfig): ContextWindowConfig = config.withMaxTokens(8000).withMinRecentTurns(2)

  def unlimited(config: ContextWindowConfig): ContextWindowConfig = config.withMaxMessages(None)

  def fresh(handoff: Handoff): Handoff = handoff.withPreserveContext(false)

  def asked(input: AgentInput, query: String): AgentInput = input.withQuery(query)
}
