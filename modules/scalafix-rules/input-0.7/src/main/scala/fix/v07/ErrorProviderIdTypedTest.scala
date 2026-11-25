/*
rule = ErrorProviderIdTyped
*/
package fix.v07

import org.llm4s.error._

import scala.concurrent.duration._

object ErrorProviderIdTypedTest {
  private val name = "openai"

  val badKey  = AuthenticationError("openai", "bad key")
  val refused = AuthenticationError(name, "bad key", "401")

  val limited  = RateLimitError("openai")
  val delayed  = RateLimitError(provider = name, retryAfter = 2.seconds)
  val throttle = RateLimitError.local(name)

  val down    = ServiceError(503, "openai", "overloaded")
  val traced  = ServiceError(503, name, "overloaded", "req-1")
  val byName  = ServiceError(status = 500, provider = "openai", details = "failed")

  val open    = CircuitOpenError.open("openai", 3.seconds)
  val probing = CircuitOpenError.probing(name)

  val embedding = EmbeddingError(Some("401"), "bad key", "voyage")

  val blocked = ContentFilteredError.prompt("gemini", "SAFETY")
  val stopped = ContentFilteredError.reply("gemini", "SAFETY", 12)
  val filter  = ContentFilteredError("gemini", "SAFETY", promptBlocked = true)

  val tooLong = ContextLengthError("openai", "too long")
  val unknown = ModelNotFoundError("ollama", "no such model")
  val part    = UnsupportedContentPartError("ollama", "audio", "user")

  // a provider passed on from another error is a ProviderId already
  def again(error: RateLimitError): RateLimitError = RateLimitError(error.provider)

  // a read keeps its type
  def fromOpenAI(error: ServiceError): Boolean = error.provider == "openai"

  def label(error: AuthenticationError): String = error.provider.toUpperCase

  def providers(errors: List[EmbeddingError]): Set[String] = errors.map(_.provider).toSet

  // a read in a string prints as it did
  def describe(error: CircuitOpenError): String = s"${error.provider} is open; ${error.message}"
}
