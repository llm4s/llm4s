package org.llm4s.llmconnect.provider

import org.llm4s.error._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HttpErrorMapperSpec extends AnyFlatSpec with Matchers {

  private val provider = "test-provider"

  // ── Status code mapping ──────────────────────────────────────────

  "HttpErrorMapper.mapHttpError" should "return AuthenticationError for 401" in {
    val result = HttpErrorMapper.mapHttpError(401, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[AuthenticationError]
  }

  it should "return AuthenticationError for 403" in {
    val result = HttpErrorMapper.mapHttpError(403, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[AuthenticationError]
  }

  it should "return RateLimitError for 429" in {
    val result = HttpErrorMapper.mapHttpError(429, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[RateLimitError]
  }

  it should "return ValidationError for 400" in {
    val result = HttpErrorMapper.mapHttpError(400, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[ValidationError]
  }

  it should "return ServiceError for 500" in {
    val result = HttpErrorMapper.mapHttpError(500, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[ServiceError]
  }

  it should "return ServiceError for 502" in {
    val result = HttpErrorMapper.mapHttpError(502, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[ServiceError]
  }

  it should "return ServiceError for 503" in {
    val result = HttpErrorMapper.mapHttpError(503, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[ServiceError]
  }

  it should "return ServiceError for unknown status codes like 418" in {
    val result = HttpErrorMapper.mapHttpError(418, "{}", provider)
    result.left.getOrElse(fail()) shouldBe a[ServiceError]
  }

  // ── Error detail extraction ──────────────────────────────────────

  "HttpErrorMapper.extractErrorDetails" should "extract top-level message field" in {
    val body = """{"message": "bad request"}"""
    HttpErrorMapper.extractErrorDetails(body, 400, provider) shouldBe "bad request"
  }

  it should "extract nested error.message field" in {
    val body = """{"error": {"message": "invalid model"}}"""
    HttpErrorMapper.extractErrorDetails(body, 400, provider) shouldBe "invalid model"
  }

  it should "extract error as plain string" in {
    val body = """{"error": "something went wrong"}"""
    HttpErrorMapper.extractErrorDetails(body, 500, provider) shouldBe "something went wrong"
  }

  it should "prefer top-level message over error.message" in {
    val body = """{"message": "top level", "error": {"message": "nested"}}"""
    HttpErrorMapper.extractErrorDetails(body, 400, provider) shouldBe "top level"
  }

  it should "fall back to default message on invalid JSON" in {
    val body = "not json"
    HttpErrorMapper.extractErrorDetails(body, 500, provider) shouldBe "test-provider API error (HTTP 500)"
  }

  it should "fall back to default message when no known fields present" in {
    val body = """{"code": 42}"""
    HttpErrorMapper.extractErrorDetails(body, 404, provider) shouldBe "test-provider API error (HTTP 404)"
  }

  it should "fall back to default message on empty body" in {
    HttpErrorMapper.extractErrorDetails("", 500, provider) shouldBe "test-provider API error (HTTP 500)"
  }

  // ── Sanitization / truncation ────────────────────────────────────

  it should "truncate long error messages" in {
    val longMessage = "x" * 1000
    val body        = s"""{"message": "$longMessage"}"""
    val result      = HttpErrorMapper.extractErrorDetails(body, 400, provider)
    result.length should be < 1000
    result should endWith("…[truncated]")
  }

  it should "trim whitespace from error messages" in {
    val body = """{"message": "  trimmed  "}"""
    HttpErrorMapper.extractErrorDetails(body, 400, provider) shouldBe "trimmed"
  }

  // ── Integration: provider name in error ──────────────────────────

  it should "include provider name in AuthenticationError" in {
    val Left(err) = HttpErrorMapper.mapHttpError(401, """{"message":"denied"}""", "gemini"): @unchecked
    err.asInstanceOf[AuthenticationError].provider shouldBe "gemini"
  }

  it should "include provider name in RateLimitError" in {
    val Left(err) = HttpErrorMapper.mapHttpError(429, "{}", "deepseek"): @unchecked
    err.asInstanceOf[RateLimitError].provider shouldBe "deepseek"
  }

  it should "include provider name in ServiceError" in {
    val Left(err) = HttpErrorMapper.mapHttpError(503, "{}", "ollama"): @unchecked
    err.asInstanceOf[ServiceError].provider shouldBe "ollama"
  }
}
