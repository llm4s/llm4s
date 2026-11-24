package fix.v1

import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }

object FromValuesKeyFirstTest {
  def openai(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(apiKey), OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(apiKey), BaseUrl(baseUrl), Some("org-1"), Some(ProviderId("requesty"))),
    OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(apiKey), baseUrl = BaseUrl(baseUrl), organization = None)
  )

  def azure(endpoint: String, apiKey: String)(using ContextWindowResolver) =
    AzureConfig.fromValues(ModelName("my-deployment"), Secret(apiKey), BaseUrl(endpoint), "2025-01-01-preview")
}
