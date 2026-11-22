/*
rule = CompressionRuleTransform
*/
package fix.v06

import org.llm4s.context.CompressionRule
import org.llm4s.llmconnect.model.Message

object CompressionRuleTransformTest {
  val keepAll: CompressionRule = CompressionRule("keep_all", apply = messages => messages)

  val positional: CompressionRule = CompressionRule("keep_all", messages => messages)

  def run(rule: CompressionRule, messages: Seq[Message]): Seq[Message] = rule.apply(messages)

  def function(rule: CompressionRule): Seq[Message] => Seq[Message] = rule.apply

  def dropAll(rule: CompressionRule): CompressionRule = rule.withApply(_ => Seq.empty)

  def dropAllNamed(rule: CompressionRule): CompressionRule = rule.withApply(apply = _ => Seq.empty)
}
