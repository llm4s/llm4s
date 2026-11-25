package fix.v07

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId

// Llm4sMigrateFrom_0_7 runs the rules for the breaks since 0.7.0 and no other: it leaves alone what the older
// umbrellas would rewrite in code compiled against 0.7.0 (a key that is a Secret already, an Instant `created`).
object Llm4sMigrateFrom_0_7Test {
  def key(config: OpenAIConfig): String = config.apiKey.reveal

  def rotated(config: OpenAIConfig): OpenAIConfig = config.withApiKey(Secret("sk-new"))

  def createdAt(completion: Completion): java.time.Instant = completion.created

  val down: LLMError = ServiceError(503, ProviderId("openai"), "overloaded")

  def fromOpenAI(error: RateLimitError): Boolean = error.provider.asString == "openai"
}
