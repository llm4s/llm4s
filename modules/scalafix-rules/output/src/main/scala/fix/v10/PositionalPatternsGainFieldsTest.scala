package fix.v10

import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelMetadata
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ModelName

// OpenAIConfig gained `api`, `reasoningSummary` and `keepReasoning`, ModelMetadata `supportedEndpoints`: a positional
// pattern with 1.0.0's eleven or eight patterns gets a `_` for each, and the field it binds keeps its position.
object PositionalPatternsGainFieldsTest {
  def modelOf(config: OpenAIConfig): ModelName = config match {
    case OpenAIConfig(_, model, _, _, _, _, _, _, _, _, _, _, _, _) => model
  }

  def mode(metadata: ModelMetadata): String = metadata match {
    case ModelMetadata(id, _, mode, _, _, _, _, _, _) => s"$id $mode"
  }

  // Not positional patterns: left as they are.
  def isOpenAI(value: Any): Boolean = value match {
    case _: OpenAIConfig => true
    case _               => false
  }

  def minimal(apiKey: Secret, model: ModelName): OpenAIConfig = OpenAIConfig(apiKey, model)
}
