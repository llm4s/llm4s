package org.llm4s.llmconnect.provider

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * The Bedrock client sends one request per call, on the synchronous and the streaming client alike: the AWS SDK's
 * own retries (four attempts in its default legacy mode) are off, so retrying is [[ReliableClient]]'s alone, under
 * its policy, deadline, breaker and metrics (#3210).
 */
final class BedrockSdkRetriesSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  /** Runs `test` against a server answering every request `status`, and returns how many requests it received. */
  private def requestsAgainst(status: Int, errorType: String)(test: String => Unit): Int = {
    val count = new AtomicInteger
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      count.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(exchange, status, errorBody(errorType, "boom"))
    }(test)
    count.get
  }

  "The Bedrock client" should "send one request for a call the service answers 503" in {
    requestsAgainst(503, "ServiceUnavailableException") { url =>
      val client = new BedrockClient(config(url))
      try client.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally client.close()
    } shouldBe 1
  }

  it should "send one request for a streamed call the service answers 503" in {
    requestsAgainst(503, "ServiceUnavailableException") { url =>
      val client = new BedrockClient(config(url))
      try client.streamComplete(hello, CompletionOptions())(_ => ()).left.value shouldBe a[ServiceError]
      finally client.close()
    } shouldBe 1
  }

  it should "send one request for a throttled call, which the SDK would retry with its longest backoff" in {
    requestsAgainst(429, "ThrottlingException") { url =>
      val client = new BedrockClient(config(url))
      try client.complete(hello, CompletionOptions()).isLeft shouldBe true
      finally client.close()
    } shouldBe 1
  }

  "A ReliableClient over the Bedrock client" should "send exactly the attempts its policy allows" in {
    val policy = ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(3, 1.milli))
    requestsAgainst(503, "ServiceUnavailableException") { url =>
      val client = ReliableClient(new BedrockClient(config(url)), "bedrock", policy)
      try client.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally client.close()
    } shouldBe 3
  }
}
