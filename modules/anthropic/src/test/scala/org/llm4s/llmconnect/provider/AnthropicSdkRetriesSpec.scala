package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.llm4s.security.Secret
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * The Anthropic client sends one request per call: anthropic-java's own retries (two by default) are off, so
 * retrying is [[ReliableClient]]'s alone, under its policy, deadline, breaker and metrics (#3210).
 */
final class AnthropicSdkRetriesSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def client(baseUrl: String): AnthropicClient =
    new AnthropicClient(
      AnthropicConfig(
        apiKey = Secret("test-key"),
        model = ModelName("claude-3-5-sonnet-latest"),
        baseUrl = BaseUrl(baseUrl),
        contextWindow = 200000,
        reserveCompletion = 4096
      )
    )

  /** Runs `test` against a server answering every request 503, and returns how many requests it received. */
  private def requestsAgainst503(test: String => Unit): Int = {
    val count = new AtomicInteger
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      count.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        503,
        """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}"""
      )
    }(test)
    count.get
  }

  "The Anthropic client" should "send one request for a call the service answers 503" in {
    requestsAgainst503 { baseUrl =>
      val c = client(baseUrl)
      try c.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally c.close()
    } shouldBe 1
  }

  it should "send one request for a streamed call the service answers 503" in {
    requestsAgainst503 { baseUrl =>
      val c = client(baseUrl)
      try c.streamComplete(hello, CompletionOptions())(_ => ()).isLeft shouldBe true
      finally c.close()
    } shouldBe 1
  }

  "A ReliableClient over the Anthropic client" should "send exactly the attempts its policy allows" in {
    val policy = ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(3, 1.milli))
    requestsAgainst503 { baseUrl =>
      val c = ReliableClient(client(baseUrl), "anthropic", policy)
      try c.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally c.close()
    } shouldBe 3
  }
}
