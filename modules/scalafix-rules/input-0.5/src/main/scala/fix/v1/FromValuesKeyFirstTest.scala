/*
rule = FromValuesKeyFirst
*/
package fix.v1

import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.types.ProviderModelTypes.ProviderId

object FromValuesKeyFirstTest {
  def openai(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues("gpt-4o", apiKey, None, OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues("gpt-4o", apiKey, Some("org-1"), baseUrl, Some(ProviderId("requesty"))),
    OpenAIConfig.fromValues("gpt-4o", apiKey, organization = None, baseUrl = baseUrl)
  )

  def azure(endpoint: String, apiKey: String)(using ContextWindowResolver) =
    AzureConfig.fromValues("my-deployment", endpoint, apiKey, "2025-01-01-preview")
}
