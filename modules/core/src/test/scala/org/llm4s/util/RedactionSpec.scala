package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RedactionSpec extends AnyFlatSpec with Matchers {

  "Redaction.secret" should "mask any string value" in {
    Redaction.secret("sk-abc123") shouldBe "***"
  }

  "Redaction.secretOpt" should "mask Some values" in {
    Redaction.secretOpt(Some("secret")) shouldBe "Some(***)"
  }

  it should "show None" in {
    Redaction.secretOpt(None) shouldBe "None"
  }

  "Redaction.truncateForLog" should "return short strings unchanged" in {
    Redaction.truncateForLog("short") shouldBe "short"
  }

  it should "truncate long strings" in {
    val long   = "A" * 3000
    val result = Redaction.truncateForLog(long, maxLength = 100)
    result.length should be < 200
    result should include("truncated")
  }

  "Redaction.redact" should "redact OpenAI API keys" in {
    val input    = "Key: sk-proj-abc123def456ghi789jkl012mno345"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("sk-proj-abc123")
  }

  it should "redact Authorization headers" in {
    val input    = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    val redacted = Redaction.redact(input)
    redacted should include("[REDACTED]")
    (redacted should not).include("eyJhbGci")
  }

  it should "redact sensitive URL query parameters" in {
    val input    = "https://api.example.com?api_key=secret123&user=john"
    val redacted = Redaction.redact(input)
    redacted should include("api_key=[REDACTED]")
    redacted should include("user=john")
  }

  it should "redact sensitive JSON fields" in {
    val input    = """{"api_key": "sk-secret", "name": "test"}"""
    val redacted = Redaction.redact(input)
    redacted should include(""""api_key": "[REDACTED]"""")
    redacted should include(""""name": "test"""")
  }

  it should "handle empty input" in {
    Redaction.redact("") shouldBe ""
  }

  it should "handle null input defensively" in {
    Redaction.redact(null) shouldBe null
  }

  it should "preserve non-sensitive content" in {
    val input = "Normal log message"
    Redaction.redact(input) shouldBe input
  }

  "Redaction.redactForLogging" should "redact and truncate" in {
    val input    = "Key: sk-proj-abc123def456ghi789jkl012mno345 " + "X" * 1000
    val redacted = Redaction.redactForLogging(input, maxLength = 100)
    redacted should include("[REDACTED]")
    (redacted should not).include("sk-proj")
    redacted should include("truncated")
  }

  "Redaction.safe" should "handle strings" in {
    val result = Redaction.safe("Key: sk-proj-abc123def456ghi789jkl012mno345")
    result should include("[REDACTED]")
  }

  it should "handle null" in {
    Redaction.safe(null) shouldBe "null"
  }
}
