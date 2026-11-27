/*
rule = Llm4sMigrateFrom_1_0
*/
package fix.v10

import org.llm4s.agent.memory.{ MemoryBounds, PostgresMemoryStore }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelMetadata
import org.llm4s.rag.RAGConfig
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ModelName

// OpenAIConfig gained `api`, `reasoningSummary` and `keepReasoning`, ModelMetadata `supportedEndpoints`: a positional
// pattern with 1.0.0's eleven or eight patterns gets a `_` for each, and the field it binds keeps its position.
object PositionalPatternsGainFieldsTest {
  def modelOf(config: OpenAIConfig): ModelName = config match {
    case OpenAIConfig(_, model, _, _, _, _, _, _, _, _, _) => model
  }

  def mode(metadata: ModelMetadata): String = metadata match {
    case ModelMetadata(id, _, mode, _, _, _, _, _) => s"$id $mode"
  }

  // MemoryBounds gained `owner`, PostgresMemoryStore.Config `tenant` (#3032, #3034)
  def maxOf(bounds: MemoryBounds): Int = bounds match {
    case MemoryBounds(max, _) => max
  }

  def tableOf(config: PostgresMemoryStore.Config): String = config match {
    case PostgresMemoryStore.Config(_, _, _, _, _, table, _) => table
  }

  // RAGConfig gained `legFailurePolicy` (#2995)
  def topK(config: RAGConfig): Int = config match {
    case RAGConfig(_, _, _, _, _, _, topK, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => topK
  }

  // Not positional patterns: left as they are.
  def isOpenAI(value: Any): Boolean = value match {
    case _: OpenAIConfig => true
    case _               => false
  }

  def minimal(apiKey: Secret, model: ModelName): OpenAIConfig = OpenAIConfig(apiKey, model)
}
