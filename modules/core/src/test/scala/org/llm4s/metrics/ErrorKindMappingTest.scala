package org.llm4s.metrics

import org.llm4s.error._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ErrorKindMappingTest extends AnyFunSuite with Matchers {

  test("fromLLMError maps RateLimitError to RateLimit") {
    val error = RateLimitError("test", 1000)
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.RateLimit
  }

  test("fromLLMError maps TimeoutError to Timeout") {
    val error = TimeoutError("Timed out", 1.second, "test-op")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Timeout
  }

  test("fromLLMError maps AuthenticationError to Authentication") {
    val error = AuthenticationError("Invalid API key", "test")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Authentication
  }

  test("fromLLMError maps NetworkError to Network") {
    val error = NetworkError("Connection failed", None, "test-endpoint")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Network
  }

  test("fromLLMError maps ValidationError to Validation") {
    val error = ValidationError("input", "Invalid input")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Validation
  }

  test("fromLLMError maps InvalidInputError to Validation") {
    val error = InvalidInputError("input", "invalid-value", "Invalid input")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Validation
  }

  test("fromLLMError maps ServiceError to ServiceError") {
    val error = ServiceError(500, "test", "Internal server error")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.ServiceError
  }

  test("fromLLMError maps ServiceError with various status codes to ServiceError") {
    ErrorKind.fromLLMError(ServiceError(400, "test", "Bad request")) shouldBe ErrorKind.ServiceError
    ErrorKind.fromLLMError(ServiceError(404, "test", "Not found")) shouldBe ErrorKind.ServiceError
    ErrorKind.fromLLMError(ServiceError(429, "test", "Rate limit")) shouldBe ErrorKind.ServiceError
    ErrorKind.fromLLMError(ServiceError(500, "test", "Server error")) shouldBe ErrorKind.ServiceError
    ErrorKind.fromLLMError(ServiceError(503, "test", "Unavailable")) shouldBe ErrorKind.ServiceError
  }

  test("fromLLMError maps ExecutionError to ExecutionError") {
    val error = ExecutionError("Execution failed", "test-op")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.ExecutionError
  }

  test("fromLLMError maps ConfigurationError to Validation") {
    val error = ConfigurationError("Missing config")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Validation
  }

  test("fromLLMError maps unknown error types to Unknown") {
    // Create a custom error type
    case class CustomError(message: String) extends LLMError

    val error = CustomError("Custom error")
    ErrorKind.fromLLMError(error) shouldBe ErrorKind.Unknown
  }

  test("ErrorKind enum contains all expected values") {
    // Verify all error kinds are defined
    val allKinds = Set(
      ErrorKind.RateLimit,
      ErrorKind.Timeout,
      ErrorKind.Authentication,
      ErrorKind.Network,
      ErrorKind.Validation,
      ErrorKind.ServiceError,
      ErrorKind.ExecutionError,
      ErrorKind.Unknown
    )

    allKinds.size shouldBe 8
  }
}
