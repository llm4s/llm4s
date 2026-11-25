package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.error.{
  AuthenticationError,
  ContextLengthError,
  LLMError,
  ModelNotFoundError,
  RateLimitError,
  ServiceError,
  TroubleshootingLinks
}
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * OpenAI's 401, 429 and 404 say the next step and link their troubleshooting entry (#2285), fed through the
 * SDK from a local server.
 */
final class OpenAITroubleshootingLinksSpec extends AnyWordSpec with Matchers {

  private given mrs: ModelRegistryService       = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given resolver: ContextWindowResolver = ContextWindowResolver(mrs)

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

  private def call(baseUrl: String): Result[org.llm4s.llmconnect.model.Completion] = {
    val client = OpenAIClient(
      OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl)).toOption.get
    ).toOption.get
    val result = client.complete(hello, CompletionOptions())
    client.close()
    result
  }

  "OpenAI replying 401" should {
    "say to check the key's project or organisation, and link #authentication-failed" in {
      val error = failure(401, """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""")
      error shouldBe an[AuthenticationError]
      assertLinked(error, TroubleshootingLinks.AuthenticationAction, Entry.AuthenticationFailed)
    }
  }

  "OpenAI replying 429" should {
    "say to lower concurrency or use ReliableClient, and link #rate-limited" in {
      val error = failure(429, """{"error":{"message":"Rate limit reached","type":"requests"}}""")
      error shouldBe a[RateLimitError]
      assertLinked(error, TroubleshootingLinks.RateLimitAction, Entry.RateLimited)
    }
  }

  "OpenAI replying 404" should {
    "say to check the model and list the provider's models, and link #model-not-found" in {
      val error =
        failure(404, """{"error":{"message":"The model `gpt-nope` does not exist","type":"invalid_request_error"}}""")
      error shouldBe a[ModelNotFoundError]
      error.httpStatus shouldBe Some(404)
      error.isRetryable shouldBe false
      assertLinked(error, TroubleshootingLinks.ModelNotFoundAction, Entry.ModelNotFound)
    }

    "be recognised by the service's code, model_not_found" in {
      val body =
        """{"error":{"message":"The model `gpt-nope` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""
      failure(404, body) shouldBe a[ModelNotFoundError]
    }

    "stay a ServiceError when the body does not say the model is unknown" in {
      val error = failure(
        404,
        """{"error":{"message":"Invalid URL (POST /v1/chat/completion)","type":"invalid_request_error"}}"""
      )
      error shouldBe a[ServiceError]
      error.httpStatus shouldBe Some(404)
    }
  }

  "OpenAI refusing a request as too long" should {
    "return a ContextLengthError with the tokens its message states" in {
      val body =
        """{"error":{"message":"This model's maximum context length is 8192 tokens. However, your messages resulted in 9431 tokens.","type":"invalid_request_error","code":"context_length_exceeded"}}"""
      failure(400, body) match {
        case e: ContextLengthError =>
          (e.limit, e.requestedTokens) shouldBe (Some(8192), Some(9431))
          e.httpStatus shouldBe Some(400)
          e.isRetryable shouldBe false
        case other => fail(s"expected a ContextLengthError, got $other")
      }
    }
  }

  "Any other 400 from OpenAI" should {
    "stay a ValidationError, carrying its status" in {
      val error = failure(400, """{"error":{"message":"max_tokens must be positive","type":"invalid_request_error"}}""")
      error shouldBe a[org.llm4s.error.ValidationError]
      error.httpStatus shouldBe Some(400)
    }
  }
}
