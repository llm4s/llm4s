package org.llm4s.error

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Tests for recoverable error types: APIError, NetworkError, ServiceError,
 * TimeoutError, ExecutionError, RateLimitError, SystemError
 */
class RecoverableErrorSpec extends AnyFlatSpec with Matchers {

  // ============ APIError ============

  "APIError" should "create with smart constructor" in {
    val error = APIError("openai", "Connection refused", Some(503), Some("Service unavailable"))

    error.provider shouldBe "openai"
    error.statusCode shouldBe Some(503)
    error.responseBody shouldBe Some("Service unavailable")
    error.message should include("API call to openai failed")
  }

  it should "be a RecoverableError" in {
    val error = APIError("anthropic", "timeout")

    error shouldBe a[RecoverableError]
    error shouldBe a[LLMError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "include context information" in {
    val error = APIError("openai", "error", Some(500), Some("body"))

    error.context should contain("provider" -> "openai")
    error.context should contain("statusCode" -> "500")
    error.context should contain("responseBody" -> "body")
  }

  it should "handle optional fields" in {
    val error = APIError("provider", "message")

    error.statusCode shouldBe None
    error.responseBody shouldBe None
    error.context should contain("provider" -> "provider")
    error.context should not contain key("statusCode")
    error.context should not contain key("responseBody")
  }

  it should "support pattern matching with unapply" in {
    val error = APIError("openai", "timeout", Some(504), None)

    error match {
      case APIError(msg, provider, statusCode, responseBody) =>
        msg should include("openai")
        provider shouldBe "openai"
        statusCode shouldBe Some(504)
        responseBody shouldBe None
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ NetworkError ============

  "NetworkError" should "create with cause" in {
    val cause = new java.net.SocketTimeoutException("Connection timed out")
    val error = NetworkError("Request failed", Some(cause), "https://api.example.com")

    error.message shouldBe "Request failed"
    error.cause shouldBe Some(cause)
    error.endpoint shouldBe "https://api.example.com"
  }

  it should "be a RecoverableError" in {
    val error = NetworkError("timeout", None, "https://api.example.com")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
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
    val error = ServiceError(503, "openai", "Service unavailable")

    error.httpStatus shouldBe 503
    error.provider shouldBe "openai"
    error.code shouldBe Some("503")
    error.message should include("Service error from openai")
  }

  it should "create with request ID" in {
    val error = ServiceError(500, "anthropic", "Internal error", "req-12345")

    error.requestId shouldBe Some("req-12345")
    error.context should contain("requestId" -> "req-12345")
    error.message should include("Request ID req-12345")
  }

  it should "be a RecoverableError" in {
    val error = ServiceError(502, "provider", "Bad gateway")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "provide retry delay" in {
    val error = ServiceError(503, "provider", "unavailable")

    error.retryDelay shouldBe Some(2.seconds)
  }

  it should "identify recoverable HTTP status codes" in {
    import ServiceError.ServiceErrorOps

    ServiceError(500, "p", "d").isRecoverableStatus shouldBe true
    ServiceError(502, "p", "d").isRecoverableStatus shouldBe true
    ServiceError(503, "p", "d").isRecoverableStatus shouldBe true
    ServiceError(429, "p", "d").isRecoverableStatus shouldBe true
    ServiceError(408, "p", "d").isRecoverableStatus shouldBe true
    ServiceError(400, "p", "d").isRecoverableStatus shouldBe false
    ServiceError(401, "p", "d").isRecoverableStatus shouldBe false
    ServiceError(404, "p", "d").isRecoverableStatus shouldBe false
  }

  it should "support pattern matching" in {
    val error = ServiceError(503, "openai", "unavailable", "req-123")

    error match {
      case ServiceError(msg, status, provider, requestId) =>
        msg should include("Service error")
        status shouldBe 503
        provider shouldBe "openai"
        requestId shouldBe Some("req-123")
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ TimeoutError ============

  "TimeoutError" should "create with duration" in {
    val error = TimeoutError("Request timed out", 30.seconds, "api-call")

    error.message shouldBe "Request timed out"
    error.timeoutDuration shouldBe 30.seconds
    error.operation shouldBe "api-call"
  }

  it should "be a RecoverableError" in {
    val error = TimeoutError("timeout", 10.seconds, "operation")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
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

  it should "be a RecoverableError" in {
    val error = ExecutionError("failed", "op")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "support withExitCode" in {
    val error = ExecutionError("failed", "script").withExitCode(1)

    error.exitCode shouldBe Some(1)
    error.context should contain("exitCode" -> "1")
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
    val error = RateLimitError("openai")

    error.provider shouldBe "openai"
    error.retryAfter shouldBe None
    error.message should include("Rate limited by openai")
  }

  it should "take its retry delay as a duration, as retryDelay reports it" in {
    val error = RateLimitError("anthropic", 1500.millis)
    error.retryDelay shouldBe Some(1500.millis)
    error.message should include("Retry after 1.5s")
  }

  it should "render a sub-second retry delay in milliseconds" in {
    RateLimitError("anthropic", 250.millis).message should include("Retry after 250ms")
  }

  it should "create with retry delay" in {
    val error = RateLimitError("anthropic", 60.seconds)

    error.retryAfter shouldBe Some(60.seconds)
    error.context should contain("retryAfter" -> "60s")
    error.message should include("Retry after 60s")
  }

  it should "be a RecoverableError" in {
    val error = RateLimitError("provider")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "have higher max retries" in {
    val error = RateLimitError("provider")

    error.maxRetries shouldBe 5
  }

  it should "calculate intelligent retry delay" in {
    val errorWithDelay = RateLimitError("p", 30.millis)
    errorWithDelay.retryDelay shouldBe Some(30.millis)

    // Without a provider hint, the default backoff applies
    RateLimitError("p").retryDelay shouldBe Some(RateLimitError.DefaultRetryDelay)
    RateLimitError.DefaultRetryDelay shouldBe 30.seconds
  }

  it should "support pattern matching" in {
    val error = RateLimitError("openai", 60.seconds)

    error match {
      case RateLimitError(msg, retryAfter, provider) =>
        msg should include("Rate limited")
        retryAfter shouldBe Some(60.seconds)
        provider shouldBe "openai"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ SystemError ============

  "SystemError" should "create basic error" in {
    val error = SystemError("Unexpected system failure")

    error.message shouldBe "Unexpected system failure"
    error.cause shouldBe None
  }

  it should "create with cause" in {
    val cause = new OutOfMemoryError("Heap space")
    val error = SystemError("Memory exhausted", Some(cause))

    error.cause shouldBe Some(cause)
    error.context should contain("exceptionType" -> "OutOfMemoryError")
  }

  it should "be a RecoverableError" in {
    val error = SystemError("system error")

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "handle missing cause in context" in {
    val error = SystemError("error")

    error.context shouldBe empty
  }

  // ============ RecoverableError Trait ============

  "RecoverableError" should "have default retry delay of None" in {
    val error = SystemError("test")

    // SystemError doesn't override retryDelay, so should use default
    // Actually SystemError is RecoverableError but doesn't have retryDelay
    error.retryDelay shouldBe None
  }

  it should "have default max retries of 3" in {
    val error = SystemError("test")

    error.maxRetries shouldBe 3
  }

  it should "always report isRecoverable as true" in {
    val errors: Seq[RecoverableError] = Seq(
      APIError("p", "m"),
      NetworkError("m", None, "e"),
      ServiceError(500, "p", "m"),
      TimeoutError("m", 1.second, "o"),
      ExecutionError("m", "o"),
      RateLimitError("p"),
      SystemError("m")
    )

    errors.foreach(error => LLMError.isRecoverable(error) shouldBe true)
  }

  // ============ Formatting ============

  "Recoverable errors" should "format correctly" in {
    val error = APIError("openai", "timeout", Some(504), None)

    val formatted = error.formatted
    formatted should include("APIError")
    formatted should include("provider=openai")
    formatted should include("statusCode=504")
  }

  it should "include correlation ID when present" in {
    // Note: Correlation ID comes from MDC, which we can't easily test without setting up MDC
    // Just verify the formatted method doesn't fail
    val error = NetworkError("test", None, "endpoint")
    error.formatted should include("NetworkError")
  }

  // ============ OptimisticLockFailure ============

  "OptimisticLockFailure" should "create with memoryId and attemptedVersion" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error.message shouldBe "conflict"
    error.memoryId shouldBe "mem-1"
    error.attemptedVersion shouldBe 2L
  }

  it should "be a RecoverableError" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error shouldBe a[RecoverableError]
    LLMError.isRecoverable(error) shouldBe true
  }

  it should "include memoryId and attemptedVersion in context" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error.context should contain("memoryId" -> "mem-1")
    error.context should contain("attemptedVersion" -> "2")
  }

  it should "support pattern matching" in {
    val error = OptimisticLockFailure("conflict", "mem-1", 2L)

    error match {
      case OptimisticLockFailure(message, memoryId, attemptedVersion) =>
        message shouldBe "conflict"
        memoryId shouldBe "mem-1"
        attemptedVersion shouldBe 2L
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ LLMError Companion Object ============

  "LLMError.recoverableErrors" should "filter only recoverable errors" in {
    val errors: List[LLMError] = List(
      APIError("p", "m"),
      AuthenticationError("p", "m"),
      RateLimitError("p"),
      ValidationError("f", "r")
    )

    val recoverable = LLMError.recoverableErrors(errors)

    recoverable should have size 2
    recoverable.foreach(_ shouldBe a[RecoverableError])
  }
}
