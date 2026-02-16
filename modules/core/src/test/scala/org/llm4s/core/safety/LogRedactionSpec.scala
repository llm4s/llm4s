package org.llm4s.core.safety

import org.llm4s.util.Redaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests that the deprecated LogRedaction wrapper correctly delegates to Redaction.
 * See [[org.llm4s.util.RedactionSpec]] for comprehensive tests.
 */
class LogRedactionSpec extends AnyFlatSpec with Matchers {

  "Redaction.redact" should "redact OpenAI API keys" in {
    val input    = "Using API key: sk-proj-abc123def456ghi789jkl012mno345"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("sk-proj-abc123")
  }

  it should "redact Anthropic API keys" in {
    val input    = "Anthropic key: sk-ant-api03-abc123def456ghi789jkl012mno"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("sk-ant-")
  }

  it should "redact Google API keys" in {
    val input    = "Google key: AIzaSyAbc123def456ghi789jkl012mno345pqr678"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("AIzaSy")
  }

  it should "redact Voyage API keys" in {
    val input    = "Voyage key: pa-abc123def456ghi789jkl012"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("pa-abc")
  }

  it should "redact Langfuse keys" in {
    val input    = "Public: pk-lf-abc123def456ghi789jkl012 Secret: sk-lf-xyz789abc123def456"
    val redacted = Redaction.redact(input)
    (redacted should not).include("pk-lf-abc")
    (redacted should not).include("sk-lf-xyz")
  }

  it should "redact Authorization headers in text" in {
    val input    = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    val redacted = Redaction.redact(input)
    redacted should include("Authorization:")
    redacted should include("[REDACTED]")
    (redacted should not).include("eyJhbGci")
    (redacted should not).include("Bearer") // Entire value is redacted
  }

  it should "redact Basic auth" in {
    val input    = "Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ="
    val redacted = Redaction.redact(input)
    redacted should include("Authorization:")
    redacted should include("[REDACTED]")
    (redacted should not).include("dXNlcm5hbWU")
    (redacted should not).include("Basic") // Entire value is redacted
  }

  it should "redact standalone Bearer tokens" in {
    // Bearer token not preceded by "Authorization:"
    val input    = "Token: Bearer abc123.def456.ghi789"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("abc123.def456")
  }

  it should "redact sensitive URL query parameters" in {
    val input    = "https://api.example.com?api_key=secret123&user=john"
    val redacted = Redaction.redact(input)
    redacted should include("api_key=[REDACTED]")
    redacted should include("user=john") // user is not sensitive
    (redacted should not).include("secret123")
  }

  it should "redact multiple sensitive query params" in {
    val input    = "https://api.example.com?key=abc&token=xyz&password=123"
    val redacted = Redaction.redact(input)
    redacted should include("key=[REDACTED]")
    redacted should include("token=[REDACTED]")
    redacted should include("password=[REDACTED]")
  }

  it should "redact sensitive JSON fields" in {
    val input    = """{"api_key": "sk-secret", "name": "test"}"""
    val redacted = Redaction.redact(input)
    redacted should include(""""api_key": "[REDACTED]"""")
    redacted should include(""""name": "test"""")
    (redacted should not).include("sk-secret")
  }

  it should "redact Authorization in JSON" in {
    val input    = """{"Authorization": "Bearer token123", "data": "value"}"""
    val redacted = Redaction.redact(input)
    redacted should include(""""Authorization": "[REDACTED]"""")
    redacted should include(""""data": "value"""")
    (redacted should not).include("token123")
  }

  it should "handle case-insensitive JSON keys" in {
    val input    = """{"apiKey": "secret", "API_KEY": "also-secret"}"""
    val redacted = Redaction.redact(input)
    (redacted should not).include("secret")
    (redacted should not).include("also-secret")
  }

  it should "handle empty input" in {
    Redaction.redact("") shouldBe ""
  }

  // Note: null handling is tested for defensive programming against Java interop,
  // but Option[String] would be preferred in pure Scala code
  it should "handle null input defensively" in {
    Redaction.redact(null) shouldBe null
  }

  it should "preserve non-sensitive content" in {
    val input    = "This is a normal log message with no secrets"
    val redacted = Redaction.redact(input)
    redacted shouldBe input
  }

  it should "use custom placeholder" in {
    val input    = "Key: sk-proj-abc123def456ghi789jkl012mno345"
    val redacted = Redaction.redact(input, "***HIDDEN***")
    redacted should include("***HIDDEN***")
    (redacted should not).include("[REDACTED]")
  }

  "Redaction.redactForLogging" should "truncate long content" in {
    val input    = "A" * 2000
    val redacted = Redaction.redactForLogging(input, maxLength = 100)
    redacted.length should be < 200 // Some overhead for truncation message
    redacted should include("truncated")
    redacted should include("chars omitted")
  }

  it should "not truncate short content" in {
    val input    = "Short message"
    val redacted = Redaction.redactForLogging(input, maxLength = 100)
    redacted shouldBe input
  }

  it should "apply redaction before truncation" in {
    val input    = "Key: sk-proj-abc123def456ghi789jkl012mno345 " + "X" * 1000
    val redacted = Redaction.redactForLogging(input, maxLength = 100)
    redacted should include("[REDACTED]")
    (redacted should not).include("sk-proj")
  }

  it should "not truncate when maxLength is 0" in {
    val input    = "A" * 2000
    val redacted = Redaction.redactForLogging(input, maxLength = 0)
    redacted shouldBe input
  }

  "Redaction.safe" should "handle strings" in {
    val result = Redaction.safe("Key: sk-proj-abc123def456ghi789jkl012mno345")
    result should include("[REDACTED]")
  }

  it should "handle null values" in {
    Redaction.safe(null) shouldBe "null"
  }

  it should "handle non-string objects" in {
    val obj    = Map("key" -> "value")
    val result = Redaction.safe(obj)
    result should include("key")
    result should include("value")
  }

  "Redaction constants" should "have default placeholder" in {
    Redaction.RedactionPlaceholder shouldBe "[REDACTED]"
  }
}
