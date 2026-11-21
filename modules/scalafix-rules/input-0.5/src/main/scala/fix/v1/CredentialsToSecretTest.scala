/*
rule = CredentialsToSecret
*/
package fix.v1

import org.llm4s.llmconnect.config.{ AnthropicConfig, OpenAICompatibleConfig, OpenAIConfig }

object CredentialsToSecretTest {
  val openai = OpenAIConfig(
    apiKey = "sk-...",
    model = "gpt-4o",
    organization = None,
    baseUrl = "https://api.openai.com/v1",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  def rotate(config: AnthropicConfig, key: String): AnthropicConfig = config.withApiKey(key)

  def local(config: OpenAICompatibleConfig, key: Option[String]): OpenAICompatibleConfig = config.withApiKey(key)

  def sameKey(config: AnthropicConfig, other: AnthropicConfig): AnthropicConfig = config.withApiKey(other.apiKey)

  def trimmed(config: AnthropicConfig, other: AnthropicConfig): AnthropicConfig = config.withApiKey(other.apiKey.trim)

  def header(config: OpenAIConfig): String = "Bearer " + config.apiKey

  def keyOf(config: OpenAICompatibleConfig): Option[String] = config.apiKey
}
