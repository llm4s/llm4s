package fix

import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, TokenUsage }
import org.llm4s.reliability.CircuitBreakerConfig

object CopyToWithSettersTest {
  val options = CompletionOptions().withMaxTokens(500).withTemperature(0.2)

  def cached(usage: TokenUsage): TokenUsage = usage.withCachedTokens(4)

  def reply(message: AssistantMessage, text: String): AssistantMessage =
    message.withContent(text)

  def local(config: OllamaConfig): OllamaConfig = config.withBaseUrl("http://localhost:11434")

  def claude(config: AnthropicConfig): AnthropicConfig =
    config
      .withModel("claude-sonnet-4-5").withContextWindow(200000)

  def breaker(config: CircuitBreakerConfig): CircuitBreakerConfig = config.withFailureThreshold(3)
}

