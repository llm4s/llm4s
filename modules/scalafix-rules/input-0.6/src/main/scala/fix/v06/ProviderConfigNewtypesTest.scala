/*
rules = [ProviderConfigNewtypes, ModelIdNewtypes]
*/
package fix.v06

import org.llm4s.config.{ DiscoveredModel, Llm4sConfig }
import org.llm4s.llmconnect.config.{ OpenAIConfig, ProviderConfig }
import org.llm4s.security.Secret
import org.llm4s.types.Result

object ProviderConfigNewtypesTest {
  val openai = OpenAIConfig(
    apiKey = Secret("sk-..."),
    model = "gpt-4o",
    organization = None,
    baseUrl = "https://api.openai.com/v1",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  def smaller(config: OpenAIConfig): OpenAIConfig = config.withModel("gpt-4o-mini")

  def other(config: ProviderConfig, model: String): ProviderConfig = config.withModel(model)

  def named(section: String): Result[ProviderConfig] = Llm4sConfig.provider(section)

  val main: Result[ProviderConfig] = Llm4sConfig.provider("openai-main")

  val models: Result[List[DiscoveredModel]] = Llm4sConfig.listModels("openai-main")

  val default: Result[ProviderConfig] = Llm4sConfig.defaultProvider()
}
