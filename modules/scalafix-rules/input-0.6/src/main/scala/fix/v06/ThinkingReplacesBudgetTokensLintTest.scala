/*
rule = ThinkingReplacesBudgetTokens
*/
package fix.v06

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort }

// What ThinkingReplacesBudgetTokens reports instead of rewriting: a budget, whose replacement is a Result, any other
// use of ReasoningEffort.None, and a match that needs cases for the new levels.
object ThinkingReplacesBudgetTokensLintTest {
  val budget = CompletionOptions().withBudgetTokens(8000) // assert: ThinkingReplacesBudgetTokens.budgetTokensRemoved

  val built = CompletionOptions(budgetTokens = Some(8000)) // assert: ThinkingReplacesBudgetTokens.budgetTokensRemoved

  def tokens(options: CompletionOptions): Option[Int] = options.budgetTokens // assert: ThinkingReplacesBudgetTokens.budgetTokensRemoved

  // Thinking.Off would take precedence over the later High, where None was replaced by it.
  val overridden = CompletionOptions().withReasoning(ReasoningEffort.None).withMaxTokens(10).withReasoning(ReasoningEffort.High) // assert: ThinkingReplacesBudgetTokens.reasoningEffortNoneRemoved

  val builtThenOverridden = CompletionOptions(reasoning = Some(ReasoningEffort.None)).withReasoning(ReasoningEffort.Low) // assert: ThinkingReplacesBudgetTokens.reasoningEffortNoneRemoved

  def off(effort: ReasoningEffort): Boolean = effort == ReasoningEffort.None // assert: ThinkingReplacesBudgetTokens.reasoningEffortNoneRemoved

  def tokensFor(effort: ReasoningEffort): Int = effort match { // assert: ThinkingReplacesBudgetTokens.reasoningEffortNewLevels
    case ReasoningEffort.Low    => 1
    case ReasoningEffort.Medium => 2
    case ReasoningEffort.High   => 3
    case ReasoningEffort.None   => 0 // assert: ThinkingReplacesBudgetTokens.reasoningEffortNoneRemoved
  }

  def label(effort: ReasoningEffort): String = effort match {
    case ReasoningEffort.High => "high"
    case _                    => "other"
  }
}
