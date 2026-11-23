/*
rule = AgentCopyToWithSetters
*/
package fix.v06

import org.llm4s.agent.{ ContextWindowConfig, Handoff }
import org.llm4s.agent.graph.toolloop.AgentInput

object AgentCopyToWithSettersTest {
  def tighter(config: ContextWindowConfig): ContextWindowConfig = config.copy(maxTokens = Some(8000), minRecentTurns = 2)

  def unlimited(config: ContextWindowConfig): ContextWindowConfig = config.copy(maxMessages = None)

  def fresh(handoff: Handoff): Handoff = handoff.copy(preserveContext = false)

  def asked(input: AgentInput, query: String): AgentInput = input.copy(query = query)
}
