package fix.v06

import org.llm4s.context.CompressionRule
import org.llm4s.llmconnect.model.Message

object CompressionRuleTransformTest {
  val keepAll: CompressionRule = CompressionRule("keep_all", transform = messages => messages)

  val positional: CompressionRule = CompressionRule("keep_all", messages => messages)

  def run(rule: CompressionRule, messages: Seq[Message]): Seq[Message] = rule.transform(messages)

  def function(rule: CompressionRule): Seq[Message] => Seq[Message] = rule.transform

  def dropAll(rule: CompressionRule): CompressionRule = rule.withTransform(_ => Seq.empty)

  def dropAllNamed(rule: CompressionRule): CompressionRule = rule.withTransform(transform = _ => Seq.empty)
}
