/*
rules = [CopyToWithSetters_1_0, ModelIdNewtypes]
*/
package fix.v1

import org.llm4s.context.CompressionRule
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.spi.EmbeddingProviderSection
import org.llm4s.toolapi.{ StringSchema, ToolExecutionConfig }
import org.llm4s.trace.TraceEvent

object CopyToWithSetters_1_0Test {
  def keepAll(rule: CompressionRule): CompressionRule = rule.copy(apply = messages => messages)

  def unbounded(config: ToolExecutionConfig): ToolExecutionConfig = config.copy(timeout = None, retryPolicy = None)

  def choices(schema: StringSchema): StringSchema = schema.copy(enumValues = Some(Seq("low", "high")))

  def renamed(toolCall: ToolCall): ToolCall = toolCall.copy(name = "search")

  def step(event: TraceEvent.CustomEvent): TraceEvent.CustomEvent = event.copy(name = "step")

  def rotate(config: DeepSeekConfig, key: String): DeepSeekConfig = config.copy(apiKey = key, model = "deepseek-chat")

  def section(embeddings: EmbeddingProviderSection, key: Option[String]): EmbeddingProviderSection =
    embeddings.copy(apiKey = key)
}
