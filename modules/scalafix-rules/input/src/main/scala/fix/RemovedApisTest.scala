/*
rule = RemovedApis
*/
package fix

import org.llm4s.agent.orchestration.CancellationToken
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.AgentId

object RemovedApisTest {
  def cancel(token: CancellationToken): Unit = token.cancel() // assert: RemovedApis.orchestrationRemoved

  def show(id: AgentId): String = id.value // assert: RemovedApis.typesIdRemoved

  def tools(registry: ToolRegistry) = registry.getToolDefinitionsSafe("openai") // assert: RemovedApis.getToolDefinitionsSafeRemoved
}
