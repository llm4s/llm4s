/*
rule = CopyToWithSetters_1_0
*/
package fix.v1

import org.llm4s.toolapi.ToolExecutionConfig

// What CopyToWithSetters_1_0 reports instead of rewriting: a positional copy, and a constructor that is private now.
object CopyToWithSetters_1_0LintTest {
  def positional(config: ToolExecutionConfig): ToolExecutionConfig = config.copy(None) // assert: CopyToWithSetters_1_0.positionalCopy

  val built = new ToolExecutionConfig(None, None) // assert: CopyToWithSetters_1_0.privateConstructor
}
