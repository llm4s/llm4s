package org.llm4s.error

import org.llm4s.syntax.toLLMError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `Safety.fromThrowable`, and `toLLMError`, its extension spelling: an exception is mapped by its type alone
 */
class SafetySpec extends AnyFlatSpec with Matchers {

  // ============ Default Error Mapper ============

  "toLLMError" should "convert SocketTimeoutException to NetworkError" in {
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

  it should "leave an exception whose message names an HTTP status an UnknownError" in {
    new RuntimeException("HTTP 401 Unauthorized").toLLMError shouldBe an[UnknownError]
    new RuntimeException("HTTP 429 Too Many Requests").toLLMError shouldBe an[UnknownError]
    new RuntimeException("429: unexpected token").toLLMError shouldBe an[UnknownError]
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

  // ============ Safety ============

  "Safety.fromThrowable" should "handle all network-related exceptions" in {
    val socketTimeout = new java.net.SocketTimeoutException("timeout")
    val connect       = new java.net.ConnectException("refused")

    Safety.fromThrowable(socketTimeout) shouldBe a[NetworkError]
    Safety.fromThrowable(connect) shouldBe a[NetworkError]
  }

  it should "preserve exception type in UnknownError context" in {
    val error = Safety.fromThrowable(new IllegalArgumentException("bad arg"))

    error shouldBe a[UnknownError]
    error.context should contain("exceptionType" -> "IllegalArgumentException")
  }

  // ============ Edge Cases ============

  "toLLMError" should "work with nested exceptions" in {
    val cause   = new java.net.SocketTimeoutException("underlying timeout")
    val wrapper = new RuntimeException("Wrapped exception", cause)

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
    error.isRetryable shouldBe true

    // Should work with LLMError utilities
    error.isRetryable shouldBe true
    error.formatted should include("NetworkError")
  }

  it should "preserve recoverability based on error type" in {
    // Network errors are recoverable
    val networkThrowable = new java.net.SocketTimeoutException("timeout")
    networkThrowable.toLLMError.isRetryable shouldBe true

    // Unknown errors are not recoverable
    val unknownThrowable = new RuntimeException("unknown")
    unknownThrowable.toLLMError.isRetryable shouldBe false
  }

  "Safety.safely" should "return the value, or the mapped exception" in {
    Safety.safely(42) shouldBe Right(42)
    val boom = new IllegalStateException("boom")
    Safety.safely[Int](throw boom).left.toOption.get shouldBe Safety.fromThrowable(boom)
  }

  "Safety.fromTry" should "return the value, or the mapped exception" in {
    Safety.fromTry(scala.util.Success(42)) shouldBe Right(42)
    val refused = new java.net.ConnectException("refused")
    Safety.fromTry(scala.util.Failure[Int](refused)).left.toOption.get shouldBe a[NetworkError]
  }
}
