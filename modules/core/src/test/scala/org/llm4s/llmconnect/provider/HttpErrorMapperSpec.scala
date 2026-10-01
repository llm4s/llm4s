package org.llm4s.llmconnect.provider

import org.llm4s.error._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class HttpErrorMapperSpec extends AnyFlatSpec with Matchers {

  private val provider = "test-provider"
  private val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T12:00:00Z"), java.time.ZoneOffset.UTC)

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

  // ── API key redaction ────────────────────────────────────────────

  it should "redact an OpenAI API key embedded in a provider error message" in {
    val key    = "sk-" + "A" * 40
    val body   = s"""{"message": "invalid key: $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "redact an Anthropic API key embedded in a provider error message" in {
    val key    = "sk-ant-" + "B" * 30
    val body   = s"""{"error": {"message": "auth failure for key $key"}}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "redact a Google API key embedded in a provider error message" in {
    val key    = "AIza" + "C" * 35
    val body   = s"""{"error": "bad api key $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 403, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "redact a Voyage API key embedded in a provider error message" in {
    val key    = "pa-" + "D" * 25
    val body   = s"""{"message": "unauthorized: $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "redact a Langfuse key embedded in a provider error message" in {
    val key    = "sk-lf-" + "E" * 20
    val body   = s"""{"message": "invalid credentials: $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "not alter messages that contain no credentials" in {
    val body   = """{"message": "model not found"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 404, provider)
    result shouldBe "model not found"
  }

  it should "redact API key in the LLMError.message returned by mapHttpError for 401" in {
    val key       = "sk-" + "F" * 40
    val body      = s"""{"message": "invalid key $key"}"""
    val Left(err) = HttpErrorMapper.mapHttpError(401, body, provider): @unchecked
    (err.message should not).include(key)
    err.message should include("[REDACTED")
  }

  it should "redact API key in the LLMError.message returned by mapHttpError for 400" in {
    val key       = "sk-ant-" + "G" * 30
    val body      = s"""{"message": "bad request with key $key"}"""
    val Left(err) = HttpErrorMapper.mapHttpError(400, body, provider): @unchecked
    (err.message should not).include(key)
    err.message should include("[REDACTED")
  }

  it should "redact API key in the LLMError.message returned by mapHttpError for 500" in {
    val key       = "AIza" + "H" * 35
    val body      = s"""{"message": "server error processing $key"}"""
    val Left(err) = HttpErrorMapper.mapHttpError(500, body, provider): @unchecked
    (err.message should not).include(key)
    err.message should include("[REDACTED")
  }

  it should "redact an AWS access key embedded in a provider error message" in {
    val key    = "AKIA" + "B" * 16
    val body   = s"""{"message": "invalid credentials: $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "redact a JWT token embedded in a provider error message" in {
    val key    = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    val body   = s"""{"message": "unauthorized token: $key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 401, provider)
    (result should not).include(key)
    result should include("[REDACTED")
  }

  it should "fully redact a key that straddles the 256-char truncation boundary" in {
    // prefix(250) + key(43) = 293 chars raw; after redact becomes prefix(250) + "[REDACTED]"(10) = 260 chars,
    // which exceeds MaxErrorDetailLength(256) and triggers truncation.
    // Without the fix (truncate-before-redact), the raw would be cut at 256 leaving a 6-char partial key.
    val key    = "sk-" + "A" * 40
    val prefix = "x" * 250
    val body   = s"""{"message": "$prefix$key"}"""
    val result = HttpErrorMapper.extractErrorDetails(body, 400, provider)
    (result should not).include(key)   // full key must not appear
    (result should not).include("sk-") // no partial key prefix either
    result should endWith("…[truncated]")
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

  // ── Retry-After on 429 ───────────────────────────────────────────

  private def rateLimit(headers: Map[String, Seq[String]]): RateLimitError =
    HttpErrorMapper.mapHttpError(429, "{}", provider, headers, clock) match {
      case Left(err: RateLimitError) => err
      case other                     => fail(s"Expected RateLimitError, got: $other")
    }

  "HttpErrorMapper.mapHttpError on 429" should "turn Retry-After delta-seconds into a delay" in {
    val err = rateLimit(Map("retry-after" -> Seq("5")))
    err.retryAfter shouldBe Some(5.seconds)
    err.retryDelay shouldBe Some(5.seconds)
  }

  it should "use the public overload with headers" in {
    val Left(err) = HttpErrorMapper.mapHttpError(429, "{}", provider, Map("retry-after" -> Seq("2"))): @unchecked
    err.asInstanceOf[RateLimitError].retryAfter shouldBe Some(2.seconds)
  }

  it should "match the header name case-insensitively and trim the value" in {
    rateLimit(Map("Retry-After" -> Seq(" 3 "))).retryAfter shouldBe Some(3.seconds)
    rateLimit(Map("RETRY-AFTER" -> Seq("4"))).retryAfter shouldBe Some(4.seconds)
  }

  it should "measure an HTTP-date against the clock" in {
    rateLimit(Map("retry-after" -> Seq("Thu, 1 Oct 2026 12:00:30 GMT"))).retryAfter shouldBe Some(30.seconds)
  }

  it should "treat an HTTP-date already past as no wait" in {
    rateLimit(Map("retry-after" -> Seq("Thu, 1 Oct 2026 11:59:00 GMT"))).retryAfter shouldBe Some(Duration.Zero)
  }

  it should "ignore an unparseable, negative or empty Retry-After" in {
    Seq("soon", "-5", "", "1.5", "99999999999999999999").foreach { value =>
      withClue(s"Retry-After: '$value'") {
        rateLimit(Map("retry-after" -> Seq(value))).retryAfter shouldBe None
      }
    }
  }

  it should "ignore a Retry-After too large to hold as a FiniteDuration" in {
    Seq("9999999999999", "Fri, 31 Dec 9999 23:59:59 GMT").foreach { value =>
      withClue(s"Retry-After: '$value'") {
        rateLimit(Map("retry-after" -> Seq(value))).retryAfter shouldBe None
      }
    }
  }

  it should "leave the delay unset without the header, so the default backoff applies" in {
    val err = rateLimit(Map.empty)
    err.retryAfter shouldBe None
    err.retryDelay shouldBe Some(30.seconds)
  }

  it should "not read Retry-After for other statuses" in {
    HttpErrorMapper.mapHttpError(503, "{}", provider, Map("retry-after" -> Seq("5")), clock) match {
      case Left(err: ServiceError) => err.httpStatus shouldBe 503
      case other                   => fail(s"Expected ServiceError, got: $other")
    }
  }
}
