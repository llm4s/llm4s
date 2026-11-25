/*
rule = PositionalPatternsGainFields
*/
package fix.v06

import org.llm4s.agent.AgentResult
import org.llm4s.agent.events.ModelCallCompleted
import org.llm4s.error.ContentFilteredError
import org.llm4s.agent.graph.toolloop.TurnState
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.model.ModelCapabilities
import org.llm4s.model.ModelPricing
import org.llm4s.rag.RAGConfig
import org.llm4s.trace.TraceEvent

object PositionalPatternsGainFieldsTest {
  def vision(capabilities: ModelCapabilities): Boolean = capabilities match {
    case ModelCapabilities(_, _, vision, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => vision.contains(true)
  }

  // gained finishReason since 0.6.0 (RFC 0009 PR 6, #2687)
  def model(event: ModelCallCompleted) = event match {
    case ModelCallCompleted(_, model, _, _, _, _, _, _) => model
  }

  // gained usage since 0.6.0, but its hand-written unapply keeps 0.6.0's four fields: left alone
  def blockedPrompt(error: ContentFilteredError): Boolean = error match {
    case ContentFilteredError(_, _, _, promptBlocked) => promptBlocked
  }

  // gained servedTier since 0.6.0 (#2750)
  def completionModel(event: TraceEvent) = event match {
    case TraceEvent.CompletionReceived(_, model, _, _, _, _, _, _, _, _, _, _, _, _) => Some(model)
    case _ => None
  }

  // gained longCacheCreationTokens since 0.6.0 (RFC 0008 PR 6, #2115)
  def prompt(usage: TokenUsage): Int = usage match {
    case TokenUsage(promptTokens, _, _, _, _, _) => promptTokens
  }

  // gained cacheCreationInputTokenCostAbove1hr since 0.6.0 (#2115)
  def inputCost(pricing: ModelPricing): Option[Double] = pricing match {
    case ModelPricing(input, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => input
  }

  // budgetTokens became thinking and promptCache was appended since 0.6.0 (#2115)
  def maxTokens(options: CompletionOptions): Option[Int] = options match {
    case CompletionOptions(_, _, maxTokens, _, _, _, _, _, _) => maxTokens
  }

  // gained cut since 0.6.0 (#2613)
  def steps(state: TurnState): Int = state match {
    case TurnState(steps, _, _, _, _) => steps
  }

  // gained completionOptions since 0.6.0 (#2715)
  def topK(config: RAGConfig): Int = config match {
    case RAGConfig(_, _, _, _, _, _, topK, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => topK
  }

  // gained runUsage since 0.6.0 (#2922)
  def requests(result: AgentResult): Long = result match {
    case AgentResult(_, _, _, _, _, usage) => usage.requestCount
  }
}
