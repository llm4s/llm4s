package fix.v06

import org.llm4s.config.{ DiscoveredModel, Llm4sConfig }
import org.llm4s.llmconnect.config.{ OpenAIConfig, ProviderConfig }
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderName }

object ProviderConfigNewtypesTest {
  val openai = OpenAIConfig(
    apiKey = Secret("sk-..."),
    model = ModelName("gpt-4o"),
    organization = None,
    baseUrl = "https://api.openai.com/v1",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  def smaller(config: OpenAIConfig): OpenAIConfig = config.withModel(ModelName("gpt-4o-mini"))

  def other(config: ProviderConfig, model: String): ProviderConfig = config.withModel(ModelName(model))

  def named(section: String): Result[ProviderConfig] = Llm4sConfig.provider(ProviderName(section))

  val main: Result[ProviderConfig] = Llm4sConfig.provider(ProviderName("openai-main"))

  val models: Result[List[DiscoveredModel]] = Llm4sConfig.listModels(ProviderName("openai-main"))

  val default: Result[ProviderConfig] = Llm4sConfig.defaultProvider()
}
