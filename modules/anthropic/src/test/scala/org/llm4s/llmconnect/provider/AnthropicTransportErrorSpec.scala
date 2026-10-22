package org.llm4s.llmconnect.provider

import org.llm4s.error.{ NetworkError, RateLimitError, TimeoutError, UnknownError }
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.RetryPolicy
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ ConnectException, ServerSocket }
import scala.concurrent.duration.*
import scala.util.Using

/**
 * The Anthropic client maps a rate limit and an I/O failure the way the other providers do (#380): a 429
 * keeps the service's `Retry-After`, and a refused connection or a timeout is a recoverable
 * `NetworkError` or `TimeoutError` - which `ReliableClient` retries - rather than an `UnknownError`.
 */
final class AnthropicTransportErrorSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def client(baseUrl: String, timeouts: ProviderTimeouts = ProviderTimeouts.default): AnthropicClient =
    new AnthropicClient(
      AnthropicConfig(
        apiKey = "test-key",
        model = "claude-3-5-haiku-latest",
        baseUrl = baseUrl,
        contextWindow = 200000,
        reserveCompletion = 4096
      ).withTimeouts(timeouts)
    )

  /** Whether `ReliableClient` would retry `error`. */
  private def retryable(error: org.llm4s.error.LLMError): Boolean = RetryPolicy.exponentialBackoff().isRetryable(error)

  "A 429" should "become a RateLimitError carrying the Retry-After header" in {
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getResponseHeaders.add("x-should-retry", "false") // the SDK must not retry on its own
      exchange.getResponseHeaders.add("Retry-After", "7")
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        429,
        """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""
      )
    } { baseUrl =>
      val error = client(baseUrl).complete(hello, CompletionOptions()).left.value
      error shouldBe a[RateLimitError]
      error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(7.seconds)
    }
  }

  it should "keep the default retry delay when the service sends no Retry-After" in {
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getResponseHeaders.add("x-should-retry", "false")
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        429,
        """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""
      )
    } { baseUrl =>
      val error = client(baseUrl).complete(hello, CompletionOptions()).left.value
      error shouldBe a[RateLimitError]
      error.asInstanceOf[RateLimitError].retryAfter shouldBe None
    }
  }

  "A base URL nothing listens on" should "fail with a retryable NetworkError" in {
    val port  = Using.resource(new ServerSocket(0))(_.getLocalPort)
    val error = client(s"http://127.0.0.1:$port").complete(hello, CompletionOptions()).left.value
    error shouldBe a[NetworkError]
    retryable(error) shouldBe true
  }

  "A server that never answers" should "fail with a retryable timeout once timeouts.request has passed" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val error = client(baseUrl.replace("localhost", "127.0.0.1"), ProviderTimeouts(request = Some(300.millis)))
        .complete(hello, CompletionOptions())
        .left
        .value
      error should (be(a[TimeoutError]).or(be(a[NetworkError])))
      retryable(error) shouldBe true
    }
  }

  "AnthropicClient.mapError" should "map an SDK I/O failure by its cause" in {
    val io = new com.anthropic.errors.AnthropicIoException("Request failed", new ConnectException("refused"))
    AnthropicClient.mapError(io, Nil) shouldBe a[NetworkError]
  }

  it should "leave an SDK I/O failure without a cause an UnknownError" in {
    AnthropicClient.mapError(new com.anthropic.errors.AnthropicIoException("Request failed"), Nil) shouldBe
      an[UnknownError]
  }
}
