package fix.v1

import org.llm4s.context.CompressionRule
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.spi.EmbeddingProviderSection
import org.llm4s.toolapi.{ StringSchema, ToolRetryPolicy }
import org.llm4s.trace.TraceEvent
import org.llm4s.security.Secret

object CopyToWithSetters_1_0Test {
  def keepAll(rule: CompressionRule): CompressionRule = rule.withTransform(messages => messages)

  def retries(policy: ToolRetryPolicy): ToolRetryPolicy = policy.withMaxAttempts(5).withBackoffFactor(1.5)

  def choices(schema: StringSchema): StringSchema = schema.withEnum(Seq("low", "high"))

  def renamed(toolCall: ToolCall): ToolCall = toolCall.withName("search")

  def step(event: TraceEvent.CustomEvent): TraceEvent.CustomEvent = event.withName("step")

  def rotate(config: DeepSeekConfig, key: String): DeepSeekConfig = config.withApiKey(Secret(key)).withModel("deepseek-chat")

  def section(embeddings: EmbeddingProviderSection, key: Option[String]): EmbeddingProviderSection =
    embeddings.withApiKey(key.map(Secret(_)))
}
