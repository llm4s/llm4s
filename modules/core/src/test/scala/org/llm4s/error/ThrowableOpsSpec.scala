package org.llm4s.error

import org.llm4s.core.safety.{ DefaultErrorMapper, ErrorMapper }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for ThrowableOps extension methods that convert Throwable to LLMError
 */
class ThrowableOpsSpec extends AnyFlatSpec with Matchers {

  import ThrowableOps._

  // ============ Default Error Mapper ============

  "ThrowableOps.toLLMError" should "convert SocketTimeoutException to NetworkError" in {
    val throwable = new java.net.SocketTimeoutException("Connection timed out")

    val error = throwable.toLLMError

    error shouldBe a[NetworkError]
    val networkError = error.asInstanceOf[NetworkError]
    networkError.message shouldBe "Request timeout"
    networkError.cause shouldBe Some(throwable)
    networkError.endpoint shouldBe "unknown"
  }

  it should "convert ConnectException to NetworkError" in {
    val throwable = new java.net.ConnectException("Connection refused")

    val error = throwable.toLLMError

    error shouldBe a[NetworkError]
    val networkError = error.asInstanceOf[NetworkError]
    networkError.message shouldBe "Connection failed"
    networkError.cause shouldBe Some(throwable)
  }

  it should "convert exception with 401 in message to AuthenticationError" in {
    val throwable = new RuntimeException("HTTP 401 Unauthorized")

    val error = throwable.toLLMError

    error shouldBe a[AuthenticationError]
    val authError = error.asInstanceOf[AuthenticationError]
    authError.provider shouldBe "unknown"
    authError.message should include("Authentication failed")
  }

  it should "convert exception with 429 in message to RateLimitError" in {
    val throwable = new RuntimeException("HTTP 429 Too Many Requests")

    val error = throwable.toLLMError

    error shouldBe a[RateLimitError]
    val rateLimitError = error.asInstanceOf[RateLimitError]
    rateLimitError.provider shouldBe "unknown"
  }

  it should "prioritize authentication error when both 401 and 429 are present in message" in {
    val throwable = new RuntimeException("HTTP 401 Unauthorized, then HTTP 429 Too Many Requests")

    val error = throwable.toLLMError

    error shouldBe a[AuthenticationError]
  }

  it should "convert unknown exception to UnknownError" in {
    val throwable = new IllegalStateException("Something unexpected happened")

    val error = throwable.toLLMError

    error shouldBe a[UnknownError]
    val unknownError = error.asInstanceOf[UnknownError]
    unknownError.message shouldBe "Something unexpected happened"
    unknownError.cause shouldBe throwable
  }

  it should "handle exception with null message" in {
    val throwable = new RuntimeException(null: String)

    val error = throwable.toLLMError

    error shouldBe a[UnknownError]
    val unknownError = error.asInstanceOf[UnknownError]
    unknownError.message shouldBe "Unknown error"
  }

  // ============ Custom Error Mapper ============

  it should "use custom ErrorMapper when provided" in {
    implicit val customMapper: ErrorMapper = new ErrorMapper {
      def apply(t: Throwable): LLMError =
        SimpleError(s"Custom: ${t.getMessage}")
    }

    val throwable = new RuntimeException("test error")

    val error = throwable.toLLMError

    error shouldBe a[SimpleError]
    error.message shouldBe "Custom: test error"
  }

  it should "use explicit mapper over implicit" in {
    val explicitMapper: ErrorMapper = new ErrorMapper {
      def apply(t: Throwable): LLMError = SimpleError("explicit")
    }

    val throwable = new RuntimeException("test")

    // Explicit mapper should be used even though DefaultErrorMapper is the implicit default
    val error = throwable.toLLMError(explicitMapper)

    error.message shouldBe "explicit"
  }

  // ============ DefaultErrorMapper ============

  "DefaultErrorMapper" should "handle all network-related exceptions" in {
    val socketTimeout = new java.net.SocketTimeoutException("timeout")
    val connect       = new java.net.ConnectException("refused")

    DefaultErrorMapper(socketTimeout) shouldBe a[NetworkError]
    DefaultErrorMapper(connect) shouldBe a[NetworkError]
  }

  it should "detect HTTP status codes in exception messages" in {
    val unauthorized = new Exception("Error: 401 - Unauthorized")
    val rateLimited  = new Exception("Error: 429 - Too Many Requests")

    DefaultErrorMapper(unauthorized) shouldBe a[AuthenticationError]
    DefaultErrorMapper(rateLimited) shouldBe a[RateLimitError]
  }

  it should "preserve exception type in UnknownError context" in {
    val error = DefaultErrorMapper(new IllegalArgumentException("bad arg"))

    error shouldBe a[UnknownError]
    error.context should contain("exceptionType" -> "IllegalArgumentException")
  }

  // ============ Edge Cases ============

  "ThrowableOps" should "work with nested exceptions" in {
    val cause   = new java.net.SocketTimeoutException("underlying timeout")
    val wrapper = new RuntimeException("Wrapped exception", cause)

    // The wrapper message doesn't contain 401/429, so it becomes UnknownError
    val error = wrapper.toLLMError

    error shouldBe a[UnknownError]
    error.message shouldBe "Wrapped exception"
  }

  it should "handle Error subclasses" in {
    val outOfMemory = new OutOfMemoryError("Java heap space")

    val error = outOfMemory.toLLMError

    error shouldBe a[UnknownError]
    error.message shouldBe "Java heap space"
  }

  it should "handle exceptions with empty message" in {
    val throwable = new RuntimeException("")

    val error = throwable.toLLMError

    error shouldBe a[UnknownError]
    // Empty string should be preserved
    error.message shouldBe ""
  }

  // ============ Type Safety ============

  "LLMError from Throwable" should "be usable in error handling patterns" in {
    val throwable = new java.net.SocketTimeoutException("timeout")
    val error     = throwable.toLLMError

    // Should be able to pattern match
    error match {
      case _: RecoverableError    => succeed
      case _: NonRecoverableError => fail("Expected RecoverableError")
    }

    // Should work with LLMError utilities
    LLMError.isRecoverable(error) shouldBe true
    error.formatted should include("NetworkError")
  }

  it should "preserve recoverability based on error type" in {
    // Network errors are recoverable
    val networkThrowable = new java.net.SocketTimeoutException("timeout")
    LLMError.isRecoverable(networkThrowable.toLLMError) shouldBe true

    // Auth errors (401) are not recoverable
    val authThrowable = new RuntimeException("HTTP 401")
    LLMError.isRecoverable(authThrowable.toLLMError) shouldBe false

    // Rate limit errors (429) are recoverable
    val rateLimitThrowable = new RuntimeException("HTTP 429")
    LLMError.isRecoverable(rateLimitThrowable.toLLMError) shouldBe true

    // Unknown errors are not recoverable
    val unknownThrowable = new RuntimeException("unknown")
    LLMError.isRecoverable(unknownThrowable.toLLMError) shouldBe false
  }
}
