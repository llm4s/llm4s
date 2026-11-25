package fix.v07

import org.llm4s.error._

import scala.concurrent.duration._
import org.llm4s.types.ProviderModelTypes.ProviderId

object ErrorProviderIdTypedTest {
  private val name = "openai"

  val badKey  = AuthenticationError(ProviderId("openai"), "bad key")
  val refused = AuthenticationError(ProviderId(name), "bad key", "401")

  val limited  = RateLimitError(ProviderId("openai"))
  val delayed  = RateLimitError(provider = ProviderId(name), retryAfter = 2.seconds)
  val throttle = RateLimitError.local(ProviderId(name))

  val down    = ServiceError(503, ProviderId("openai"), "overloaded")
  val traced  = ServiceError(503, ProviderId(name), "overloaded", "req-1")
  val byName  = ServiceError(status = 500, provider = ProviderId("openai"), details = "failed")

  val open    = CircuitOpenError.open(ProviderId("openai"), 3.seconds)
  val probing = CircuitOpenError.probing(ProviderId(name))

  val embedding = EmbeddingError(Some("401"), "bad key", ProviderId("voyage"))

  val blocked = ContentFilteredError.prompt(ProviderId("gemini"), "SAFETY")
  val stopped = ContentFilteredError.reply(ProviderId("gemini"), "SAFETY", 12)
  val filter  = ContentFilteredError(ProviderId("gemini"), "SAFETY", promptBlocked = true)

  val tooLong = ContextLengthError(ProviderId("openai"), "too long")
  val unknown = ModelNotFoundError(ProviderId("ollama"), "no such model")
  val part    = UnsupportedContentPartError(ProviderId("ollama"), "audio", "user")

  // a provider passed on from another error is a ProviderId already
  def again(error: RateLimitError): RateLimitError = RateLimitError(error.provider)

  // a read keeps its type
  def fromOpenAI(error: ServiceError): Boolean = error.provider.asString == "openai"

  def label(error: AuthenticationError): String = error.provider.asString.toUpperCase

  def providers(errors: List[EmbeddingError]): Set[String] = errors.map(_.provider.asString).toSet

  // a read in a string prints as it did
  def describe(error: CircuitOpenError): String = s"${error.provider} is open; ${error.message}"
}
