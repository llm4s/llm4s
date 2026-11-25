package org.llm4s.error

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * Pins the text an error carries its provider in: the `message` and the `context` entry, which are what reaches a log
 * line, a trace, a stored failure and the Java facade. The provider is typed (a `ProviderId`); the text is not, and
 * must not change with the type. Written against the `String` field of 0.7.0 first.
 */
class ErrorProviderTextSpec extends AnyFlatSpec with Matchers {

  private val provider = ProviderId("openai")

  "An error's provider" should "read the same in the message and the context of every error that has one" in {
    val errors: List[(LLMError, String)] = List(
      AuthenticationError(provider, "bad key")        -> "Authentication failed for openai: bad key",
      AuthenticationError(provider, "bad key", "401") -> "Authentication failed for openai: bad key",
      RateLimitError(provider)                        -> "Rate limited by openai",
      RateLimitError(provider, 2.seconds)             -> "Rate limited by openai. Retry after 2s",
      RateLimitError.local(provider)                  -> "Local rate limit exceeded.",
      ServiceError(503, provider, "down")             -> "Service error from openai: down (HTTP 503)",
      ServiceError(503, provider, "down", "req-1") ->
        "Service error from openai: down (HTTP 503) (Request ID req-1)",
      CircuitOpenError.open(provider, 3.seconds) ->
        "Circuit breaker is open for openai: the call was not made. Retry after 3s",
      CircuitOpenError.probing(provider) ->
        "Circuit breaker is half-open for openai: a probe is already in progress, and the call was not made",
      EmbeddingError(Some("500"), "boom", provider)   -> "boom",
      ContentFilteredError.prompt(provider, "SAFETY") -> "openai blocked the prompt: blockReason SAFETY",
      ContentFilteredError.reply(provider, "SAFETY")  -> "openai returned no content: finishReason SAFETY",
      ContentFilteredError.reply(provider, "SAFETY", 12) ->
        "openai stopped the reply after 12 characters of text: finishReason SAFETY",
      ContextLengthError(provider, "too long") ->
        "openai refused the request: it does not fit the model's context window: too long",
      ModelNotFoundError(provider, "no such model") -> "openai does not know the model: no such model",
      UnsupportedContentPartError(provider, "audio", "user") ->
        "openai cannot send a audio part in a user message"
    )
    errors.foreach { case (error, message) =>
      withClue(error.getClass.getSimpleName) {
        error.message shouldBe message
        error.context.get("provider") shouldBe Some("openai")
      }
    }
  }
}
