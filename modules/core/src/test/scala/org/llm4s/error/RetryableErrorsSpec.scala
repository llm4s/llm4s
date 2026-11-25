package org.llm4s.error

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Tests for recoverable error types: NetworkError, ServiceError,
 * TimeoutError, ExecutionError, RateLimitError
 */
class RetryableErrorsSpec extends AnyFlatSpec with Matchers {

  // ============ NetworkError ============

  "NetworkError" should "create with cause" in {
    val cause = new java.net.SocketTimeoutException("Connection timed out")
    val error = NetworkError("Request failed", Some(cause), "https://api.example.com")

    error.message shouldBe "Request failed"
    error.cause shouldBe Some(cause)
    error.endpoint shouldBe "https://api.example.com"
  }

  it should "be retryable" in {
    val error = NetworkError("timeout", None, "https://api.example.com")

    error.isRetryable shouldBe true
  }

  it should "include exception type in context" in {
    val cause = new java.io.IOException("Network error")
    val error = NetworkError("failed", Some(cause), "endpoint")

    error.context should contain("endpoint" -> "endpoint")
    error.context should contain("exceptionType" -> "IOException")
  }

  it should "handle missing cause" in {
    val error = NetworkError("Connection refused", None, "https://api.openai.com")

    error.cause shouldBe None
    error.context should contain("endpoint" -> "https://api.openai.com")
    error.context should not contain key("exceptionType")
  }

  it should "support pattern matching" in {
    val error = NetworkError("timeout", None, "https://example.com")

    error match {
      case NetworkError(msg, cause, endpoint) =>
        msg shouldBe "timeout"
        cause shouldBe None
        endpoint shouldBe "https://example.com"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ServiceError ============

  "ServiceError" should "create with HTTP status" in {
    val error = ServiceError(503, ProviderId("openai"), "Service unavailable")

    error.status shouldBe 503
    error.provider shouldBe ProviderId("openai")
    error.code shouldBe Some("503")
    error.message should include("Service error from openai")
  }

  it should "create with request ID" in {
    val error = ServiceError(500, ProviderId("anthropic"), "Internal error", "req-12345")

    error.requestId shouldBe Some("req-12345")
    error.context should contain("requestId" -> "req-12345")
    error.message should include("Request ID req-12345")
  }

  it should "be retryable" in {
    val error = ServiceError(502, ProviderId("provider"), "Bad gateway")

    error.isRetryable shouldBe true
  }

  it should "report no retryAfter unless the provider gave one" in {
    val error = ServiceError(503, ProviderId("provider"), "unavailable")

    error.retryAfter shouldBe None
    error.withRetryAfter(7.seconds).retryAfter shouldBe Some(7.seconds)
  }

  it should "report its status as httpStatus" in {
    ServiceError(503, ProviderId("provider"), "unavailable").httpStatus shouldBe Some(503)
  }

  it should "identify recoverable HTTP status codes" in {
    ServiceError(500, ProviderId("p"), "d").isRetryable shouldBe true
    ServiceError(502, ProviderId("p"), "d").isRetryable shouldBe true
    ServiceError(503, ProviderId("p"), "d").isRetryable shouldBe true
    ServiceError(429, ProviderId("p"), "d").isRetryable shouldBe true
    ServiceError(408, ProviderId("p"), "d").isRetryable shouldBe true
    ServiceError(400, ProviderId("p"), "d").isRetryable shouldBe false
    ServiceError(401, ProviderId("p"), "d").isRetryable shouldBe false
    ServiceError(404, ProviderId("p"), "d").isRetryable shouldBe false
  }

  it should "support pattern matching" in {
    val error = ServiceError(503, ProviderId("openai"), "unavailable", "req-123")

    error match {
      case ServiceError(msg, status, provider, requestId, retryAfter) =>
        msg should include("Service error")
        status shouldBe 503
        provider shouldBe ProviderId("openai")
        requestId shouldBe Some("req-123")
        retryAfter shouldBe None
    }
  }

  // ============ TimeoutError ============

  "TimeoutError" should "create with duration" in {
    val error = TimeoutError("Request timed out", 30.seconds, "api-call")

    error.message shouldBe "Request timed out"
    error.timeoutDuration shouldBe 30.seconds
    error.operation shouldBe "api-call"
  }

  it should "be retryable" in {
    val error = TimeoutError("timeout", 10.seconds, "operation")

    error.isRetryable shouldBe true
  }

  it should "support withContext" in {
    val error = TimeoutError("timeout", 5.seconds, "op")
      .withContext("key", "value")
      .withContext(Map("key2" -> "value2"))

    error.context should contain("key" -> "value")
    error.context should contain("key2" -> "value2")
  }

  it should "support withOperation" in {
    val error = TimeoutError("timeout", 5.seconds, "original")
      .withOperation("updated")

    error.operation shouldBe "updated"
    error.context should contain("operation" -> "updated")
  }

  it should "optionally include cause" in {
    val cause = new java.util.concurrent.TimeoutException()
    val error = TimeoutError("timeout", 10.seconds, "op", Some(cause))

    error.cause shouldBe Some(cause)
  }

  it should "support pattern matching" in {
    val error = TimeoutError("timeout", 10.seconds, "op")

    error match {
      case TimeoutError(message, timeoutDuration, operation, cause, context) =>
        message shouldBe "timeout"
        timeoutDuration shouldBe 10.seconds
        operation shouldBe "op"
        cause shouldBe None
        context shouldBe empty
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ExecutionError ============

  "ExecutionError" should "create basic error" in {
    val error = ExecutionError("Command failed", "bash-script")

    error.message shouldBe "Command failed"
    error.operation shouldBe "bash-script"
    error.exitCode shouldBe None
  }

  it should "be retryable" in {
    val error = ExecutionError("failed", "op")

    error.isRetryable shouldBe true
  }

  it should "support withContext" in {
    val error = ExecutionError("failed", "op")
      .withContext("key", "value")
      .withContext(Map("a" -> "b", "c" -> "d"))

    error.context should contain("key" -> "value")
    error.context should contain("a" -> "b")
    error.context should contain("c" -> "d")
  }

  it should "optionally include cause" in {
    val cause = new RuntimeException("Underlying error")
    val error = ExecutionError("failed", "op", cause = Some(cause))

    error.cause shouldBe Some(cause)
  }

  it should "support pattern matching" in {
    val error = ExecutionError("failed", "op")

    error match {
      case ExecutionError(message, operation, exitCode, cause, context) =>
        message shouldBe "failed"
        operation shouldBe "op"
        exitCode shouldBe None
        cause shouldBe None
        context shouldBe empty
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ RateLimitError ============

  "RateLimitError" should "create basic error" in {
    val error = RateLimitError(ProviderId("openai"))

    error.provider shouldBe ProviderId("openai")
    error.retryAfter shouldBe None
    error.message should include("Rate limited by openai")
  }

  it should "take its retry delay as a duration, as retryAfter reports it" in {
    val error = RateLimitError(ProviderId("anthropic"), 1500.millis)
    error.retryAfter shouldBe Some(1500.millis)
    error.message should include("Retry after 1.5s")
  }

  it should "render a sub-second retry delay in milliseconds" in {
    RateLimitError(ProviderId("anthropic"), 250.millis).message should include("Retry after 250ms")
  }

  it should "create with retry delay" in {
    val error = RateLimitError(ProviderId("anthropic"), 60.seconds)

    error.retryAfter shouldBe Some(60.seconds)
    error.context should contain("retryAfter" -> "60s")
    error.message should include("Retry after 60s")
  }

  it should "be retryable" in {
    val error = RateLimitError(ProviderId("provider"))

    error.isRetryable shouldBe true
  }

  it should "report only the delay the provider gave as retryAfter" in {
    RateLimitError(ProviderId("p"), 30.millis).retryAfter shouldBe Some(30.millis)

    // Without a provider hint there is none: the wait is the retry policy's decision
    RateLimitError(ProviderId("p")).retryAfter shouldBe None
    org.llm4s.reliability.RetryPolicy.DefaultRateLimitDelay shouldBe 30.seconds
  }

  it should "carry the HTTP status and request id it is given, and none otherwise" in {
    val bare = RateLimitError(ProviderId("p"))
    bare.httpStatus shouldBe None
    bare.requestId shouldBe None

    val full = bare.withHttpStatus(429).withRequestId("req-1")
    full.httpStatus shouldBe Some(429)
    full.requestId shouldBe Some("req-1")
    full.context should contain("requestId" -> "req-1")
    RateLimitError.local(ProviderId("p")).httpStatus shouldBe None
  }

  it should "support pattern matching" in {
    val error = RateLimitError(ProviderId("openai"), 60.seconds)

    error match {
      case RateLimitError(msg, retryAfter, provider) =>
        msg should include("Rate limited")
        retryAfter shouldBe Some(60.seconds)
        provider shouldBe ProviderId("openai")
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ Retryable ============

  "A transient error" should "be retryable" in {
    val errors: Seq[LLMError] = Seq(
      NetworkError("m", None, "e"),
      ServiceError(500, ProviderId("p"), "m"),
      TimeoutError("m", 1.second, "o"),
      ExecutionError("m", "o"),
      RateLimitError(ProviderId("p")),
    )

    errors.foreach(error => error.isRetryable shouldBe true)
  }

  // ============ Formatting ============

  "Recoverable errors" should "include correlation ID when present" in {
    // Note: Correlation ID comes from MDC, which we can't easily test without setting up MDC
    // Just verify the formatted method doesn't fail
    val error = NetworkError("test", None, "endpoint")
    error.formatted should include("NetworkError")
  }

  // ============ OptimisticLockFailure ============

  "OptimisticLockFailure" should "create with recordId and attemptedVersion" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error.message shouldBe "conflict"
    error.recordId shouldBe "mem-1"
    error.attemptedVersion shouldBe 2L
  }

  it should "not be retryable: the same update fails until the record is re-read" in {
    OptimisticLockFailure("conflict", "mem-1", 2L).isRetryable shouldBe false
  }

  it should "include recordId and attemptedVersion in context" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error.context should contain("recordId" -> "mem-1")
    error.context should contain("attemptedVersion" -> "2")
  }

  it should "support pattern matching" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error match {
      case OptimisticLockFailure(message, recordId, attemptedVersion) =>
        message shouldBe "conflict"
        recordId shouldBe "mem-1"
        attemptedVersion shouldBe 2L
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ LLMError Companion Object ============

  "LLMError.recoverableErrors" should "filter only recoverable errors" in {
    val errors: List[LLMError] = List(
      AuthenticationError(ProviderId("p"), "m"),
      RateLimitError(ProviderId("p")),
      ServiceError(503, ProviderId("p"), "m"),
      ValidationError("f", "r")
    )

    val recoverable = errors.filter(_.isRetryable)

    recoverable should have size 2
    recoverable.foreach(_.isRetryable shouldBe true)
  }
}
