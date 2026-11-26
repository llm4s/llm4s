package fix.v07

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.security.Secret
import org.llm4s.model.ModelMetadata
import org.llm4s.types.ProviderModelTypes.ProviderId

// Llm4sMigrateFrom_0_7 runs the rules for the breaks since 0.7.0 (here the positional patterns on OpenAIConfig and
// ModelMetadata, which gained fields) and no other: it leaves alone what the older umbrellas would rewrite in code
// compiled against 0.7.0 (a key that is a Secret already, an Instant `created`).
object Llm4sMigrateFrom_0_7Test {
  def key(config: OpenAIConfig): String = config.apiKey.reveal

  def rotated(config: OpenAIConfig): OpenAIConfig = config.withApiKey(Secret("sk-new"))

  def createdAt(completion: Completion): java.time.Instant = completion.created

  val down: LLMError = ServiceError(503, ProviderId("openai"), "overloaded")

  def fromOpenAI(error: RateLimitError): Boolean = error.provider.asString == "openai"

  // A positional pattern on OpenAIConfig gets a `_` for each field added since 0.7, and the model keeps its place.
  def modelLength(config: OpenAIConfig): Int = config match {
    case OpenAIConfig(_, model, _, _, _, _, _, _, _, _, _, _, _, _) => model.toString.length
  }

  def metadataMode(metadata: ModelMetadata): String = metadata match {
    case ModelMetadata(id, _, mode, _, _, _, _, _, _) => s"$id $mode"
  }
}
