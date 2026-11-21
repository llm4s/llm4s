package fix.v06

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort }
import org.llm4s.llmconnect.model.Thinking

object ThinkingReplacesBudgetTokensTest {
  val off = CompletionOptions().withThinking(Thinking.Off)

  val offOption = CompletionOptions().withMaxTokens(100).withThinking(Thinking.Off)

  val built = CompletionOptions(temperature = 0.2, thinking = Some(Thinking.Off))

  val high = CompletionOptions().withReasoning(ReasoningEffort.High)
}
