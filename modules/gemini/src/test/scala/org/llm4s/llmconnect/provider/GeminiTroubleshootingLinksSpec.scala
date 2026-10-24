package org.llm4s.llmconnect.provider

import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, ServiceError, TroubleshootingLinks }
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Gemini's 401, 429 and 404, and its 400 for a key that is not valid, say the next step and link their
 * troubleshooting entry (#2285), fed from a local server.
 */
final class GeminiTroubleshootingLinksSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  /** The error a call fails with when the server answers `status` with `body`. */
  private def failure(status: Int, body: String): LLMError = {
    var result: Option[LLMError] = None
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      exchange.getResponseHeaders.add("x-should-retry", "false") // an SDK must not retry on its own
      LocalProviderTestServer.sendJsonResponse(exchange, status, body)
    } { baseUrl =>
      result = Some(call(baseUrl).fold(identity, completion => fail(s"expected HTTP $status to fail, got $completion")))
    }
    result.getOrElse(fail("the server was not called"))
  }

  private def assertLinked(error: LLMError, sentence: String, entry: Entry): Unit = {
    error.message should include(sentence)
    error.message should include(TroubleshootingLinks.forId(entry))
  }

  private def call(baseUrl: String): Result[org.llm4s.llmconnect.model.Completion] =
    new GeminiClient(GeminiConfig("test-key", "gemini-2.0-flash", baseUrl, 1048576, 8192))
      .complete(hello, CompletionOptions())

  private def googleError(code: Int, status: String, message: String): String =
    s"""{"error":{"code":$code,"message":"$message","status":"$status"}}"""

  "Gemini replying 401" should {
    "say to check the key's project or organisation, and link #authentication-failed" in {
      val error = failure(401, googleError(401, "UNAUTHENTICATED", "Request had invalid authentication credentials."))
      error shouldBe an[AuthenticationError]
      assertLinked(error, TroubleshootingLinks.AuthenticationAction, Entry.AuthenticationFailed)
    }
  }

  "Gemini replying 429" should {
    "say to lower concurrency or use ReliableClient, and link #rate-limited" in {
      val error = failure(429, googleError(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted"))
      error shouldBe a[RateLimitError]
      assertLinked(error, TroubleshootingLinks.RateLimitAction, Entry.RateLimited)
    }
  }

  "Gemini replying 404" should {
    "say to check the model and list the provider's models, and link #model-not-found" in {
      val error = failure(404, googleError(404, "NOT_FOUND", "models/gemini-nope is not found"))
      error shouldBe a[ServiceError]
      assertLinked(error, TroubleshootingLinks.ModelNotFoundAction, Entry.ModelNotFound)
    }
  }

  "Gemini replying 400 for a key that is not valid" should {
    "be an AuthenticationError that links #authentication-failed" in {
      val error = failure(400, googleError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."))
      error shouldBe an[AuthenticationError]
      assertLinked(error, TroubleshootingLinks.AuthenticationAction, Entry.AuthenticationFailed)
    }
  }
}
