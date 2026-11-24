package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.error.{
  AuthenticationError,
  ContextLengthError,
  LLMError,
  ModelNotFoundError,
  NetworkError,
  RateLimitError,
  ServiceError,
  TimeoutError,
  UnknownError,
  ValidationError
}
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
        apiKey = Secret("test-key"),
        model = ModelName("claude-3-5-haiku-latest"),
        baseUrl = BaseUrl(baseUrl),
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
    AnthropicClient.mapError(io) shouldBe a[NetworkError]
  }

  it should "leave an SDK I/O failure without a cause an UnknownError" in {
    AnthropicClient.mapError(new com.anthropic.errors.AnthropicIoException("Request failed")) shouldBe
      an[UnknownError]
  }

  // ---- the one error model (#2916): typed errors, and the reply's status and request id on every error

  /** The error a `complete` gets from a service that answers `status` with `body` and a `request-id` header. */
  private def refused(status: Int, body: String, headers: (String, String)*): LLMError = {
    var error = Option.empty[LLMError]
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getResponseHeaders.add("x-should-retry", "false") // the SDK must not retry on its own
      exchange.getResponseHeaders.add("request-id", "req_011")
      headers.foreach((name, value) => exchange.getResponseHeaders.add(name, value))
      LocalProviderTestServer.sendJsonResponse(exchange, status, body)
    }(baseUrl => error = Some(client(baseUrl).complete(hello, CompletionOptions()).left.value))
    error.getOrElse(fail("the call did not run"))
  }

  private def errorBody(kind: String, message: String): String =
    ujson.Obj("type" -> "error", "error" -> ujson.Obj("type" -> kind, "message" -> message)).render()

  "A 400 saying the prompt is too long" should "be a ContextLengthError with the limit and the requested tokens" in {
    refused(400, errorBody("invalid_request_error", "prompt is too long: 213462 tokens > 200000 maximum")) match {
      case e: ContextLengthError =>
        e.limit shouldBe Some(200000)
        e.requestedTokens shouldBe Some(213462)
        e.httpStatus shouldBe Some(400)
        e.requestId shouldBe Some("req_011")
        e.provider shouldBe "anthropic"
        retryable(e) shouldBe false
      case other => fail(s"expected a ContextLengthError, got $other")
    }
  }

  "Any other 400" should "stay a ValidationError on field `request`, carrying its status and request id" in {
    refused(400, errorBody("invalid_request_error", "max_tokens: must be at least 1")) match {
      case e: ValidationError =>
        e.field shouldBe "request"
        e.httpStatus shouldBe Some(400)
        e.requestId shouldBe Some("req_011")
      case other => fail(s"expected a ValidationError, got $other")
    }
  }

  "A 404 naming the model" should "be a ModelNotFoundError" in {
    refused(404, errorBody("not_found_error", "model: claude-nope")) match {
      case e: ModelNotFoundError =>
        e.httpStatus shouldBe Some(404)
        e.requestId shouldBe Some("req_011")
        e.message should include("troubleshooting#model-not-found")
        retryable(e) shouldBe false
      case other => fail(s"expected a ModelNotFoundError, got $other")
    }
  }

  "A 404 that names no model" should "stay a ServiceError that is not retried" in {
    refused(404, errorBody("not_found_error", "no such endpoint")) match {
      case e: ServiceError =>
        e.status shouldBe 404
        e.requestId shouldBe Some("req_011")
        retryable(e) shouldBe false
      case other => fail(s"expected a ServiceError, got $other")
    }
  }

  "A 401" should "be an AuthenticationError carrying its status and request id" in {
    refused(401, errorBody("authentication_error", "invalid x-api-key")) match {
      case e: AuthenticationError =>
        e.httpStatus shouldBe Some(401)
        e.requestId shouldBe Some("req_011")
      case other => fail(s"expected an AuthenticationError, got $other")
    }
  }

  "A 429" should "carry its status and request id as well as its Retry-After" in {
    val error = refused(429, errorBody("rate_limit_error", "slow down"), "Retry-After" -> "3")
    error shouldBe a[RateLimitError]
    error.httpStatus shouldBe Some(429)
    error.requestId shouldBe Some("req_011")
    error.retryAfter shouldBe Some(3.seconds)
  }

  "An overloaded 529" should "be a retryable ServiceError carrying the service's Retry-After" in {
    refused(529, errorBody("overloaded_error", "Overloaded"), "Retry-After" -> "9") match {
      case e: ServiceError =>
        e.status shouldBe 529
        e.retryAfter shouldBe Some(9.seconds)
        e.requestId shouldBe Some("req_011")
        retryable(e) shouldBe true
      case other => fail(s"expected a ServiceError, got $other")
    }
  }
}
