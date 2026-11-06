/*
rule = CopyToWithSetters
*/
package fix

import org.llm4s.context.ContextConfig
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }

// What CopyToWithSetters reports instead of rewriting: a positional argument, and a field with no setter.
object CopyToWithSettersLintTest {
  def positional(usage: TokenUsage): TokenUsage = usage.copy(1) // assert: CopyToWithSetters.positionalCopy

  def thinking(completion: Completion): Completion = completion.copy(thinking = Some("...")) // assert: CopyToWithSetters.fieldWithoutSetter

  def summary(config: ContextConfig): ContextConfig = config.copy(enableRollingSummary = false) // assert: CopyToWithSetters.fieldWithoutSetter
}
