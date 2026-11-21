/*
rule = CopyToWithSetters_1_0
*/
package fix.v1

import org.llm4s.toolapi.ToolRetryPolicy

import scala.concurrent.duration._

// What CopyToWithSetters_1_0 reports instead of rewriting: a positional copy, and a constructor that is private now.
object CopyToWithSetters_1_0LintTest {
  def positional(policy: ToolRetryPolicy): ToolRetryPolicy = policy.copy(3) // assert: CopyToWithSetters_1_0.positionalCopy

  val built = new ToolRetryPolicy(3, 1.second, 2.0) // assert: CopyToWithSetters_1_0.privateConstructor
}
