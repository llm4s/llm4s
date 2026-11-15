package fix.v1

import org.llm4s.llmconnect.config.{ AnthropicConfig, OpenAICompatibleConfig, OpenAIConfig }
import org.llm4s.security.Secret

object CredentialsToSecretTest {
  val openai = OpenAIConfig(
    apiKey = Secret("sk-..."),
    model = "gpt-4o",
    organization = None,
    baseUrl = "https://api.openai.com/v1",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  def rotate(config: AnthropicConfig, key: String): AnthropicConfig = config.withApiKey(Secret(key))

  def local(config: OpenAICompatibleConfig, key: Option[String]): OpenAICompatibleConfig = config.withApiKey(key.map(Secret(_)))

  def sameKey(config: AnthropicConfig, other: AnthropicConfig): AnthropicConfig = config.withApiKey(other.apiKey)

  def trimmed(config: AnthropicConfig, other: AnthropicConfig): AnthropicConfig = config.withApiKey(Secret(other.apiKey.reveal.trim))

  def header(config: OpenAIConfig): String = "Bearer " + config.apiKey.reveal

  def keyOf(config: OpenAICompatibleConfig): Option[String] = config.apiKey.map(_.reveal)
}
