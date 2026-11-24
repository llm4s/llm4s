/*
rule = ThinkingReplacesBudgetTokens
*/
package fix.v06

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort }

object ThinkingReplacesBudgetTokensTest {
  val off = CompletionOptions().withReasoning(ReasoningEffort.None)

  val offOption = CompletionOptions().withMaxTokens(100).withReasoning(Some(ReasoningEffort.None))

  val built = CompletionOptions(maxTokens = Some(100), reasoning = Some(ReasoningEffort.None))

  val high = CompletionOptions().withReasoning(ReasoningEffort.High)
}
