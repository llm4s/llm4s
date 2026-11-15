/*
rule = CopyToWithSetters
*/
package fix

import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, TokenUsage }
import org.llm4s.reliability.CircuitBreakerConfig

object CopyToWithSettersTest {
  val options = CompletionOptions().copy(maxTokens = Some(500), temperature = 0.2)

  def cached(usage: TokenUsage): TokenUsage = usage.copy(cachedTokens = Some(4))

  def reply(message: AssistantMessage, text: String): AssistantMessage =
    message.copy(contentOpt = Some(text))

  def local(config: OllamaConfig): OllamaConfig = config.copy(baseUrl = "http://localhost:11434")

  def claude(config: AnthropicConfig): AnthropicConfig =
    config
      .copy(
        model = "claude-sonnet-4-5",
        contextWindow = 200000
      )

  def breaker(config: CircuitBreakerConfig): CircuitBreakerConfig = config.copy(failureThreshold = 3)
}
