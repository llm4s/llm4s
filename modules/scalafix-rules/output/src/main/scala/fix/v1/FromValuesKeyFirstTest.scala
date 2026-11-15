package fix.v1

import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.security.Secret

object FromValuesKeyFirstTest {
  def openai(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues("gpt-4o", Secret(apiKey), OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues("gpt-4o", Secret(apiKey), baseUrl, Some("org-1"), Some(ProviderId("requesty"))),
    OpenAIConfig.fromValues("gpt-4o", Secret(apiKey), baseUrl = baseUrl, organization = None)
  )

  def azure(endpoint: String, apiKey: String)(using ContextWindowResolver) =
    AzureConfig.fromValues("my-deployment", Secret(apiKey), endpoint, "2025-01-01-preview")
}
