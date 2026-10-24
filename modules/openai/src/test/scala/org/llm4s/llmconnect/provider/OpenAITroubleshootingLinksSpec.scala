package org.llm4s.llmconnect.provider

import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, ServiceError, TroubleshootingLinks }
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
    val client = OpenAIClient(OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).toOption.get).toOption.get
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
      error shouldBe a[ServiceError]
      assertLinked(error, TroubleshootingLinks.ModelNotFoundAction, Entry.ModelNotFound)
    }
  }
}
