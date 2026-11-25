/*
rule = Llm4sMigrateFrom_1_0
*/
package fix.v10

import org.llm4s.error.{ LLMError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId

// Nothing has broken since 1.0.0, so Llm4sMigrateFrom_1_0 runs no rule: it is discovered by name and leaves code
// compiled against 1.0.0 alone, including what the older umbrellas would rewrite (a key that is a Secret already, an
// Instant `created`, an error whose provider is a ProviderId). Its first rule's fixtures join this directory.
object Llm4sMigrateFrom_1_0ChangesNothingTest {
  def key(config: OpenAIConfig): String = config.apiKey.reveal

  def rotated(config: OpenAIConfig): OpenAIConfig = config.withApiKey(Secret("sk-new"))

  def createdAt(completion: Completion): java.time.Instant = completion.created

  val down: LLMError = ServiceError(503, ProviderId("openai"), "overloaded")

  def fromOpenAI(error: RateLimitError): Boolean = error.provider.asString == "openai"
}
