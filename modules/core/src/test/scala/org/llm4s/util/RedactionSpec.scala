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

  // A provider error body that echoes the request's credentials back, in the three shapes #1674 lists.
  private def echoedBody: String =
    """{"error": {"message": "bad request", "request": {"headers": "Authorization: Bearer sk-proj-abc123def456ghi789jkl012mno345",""" +
      """ "api_key": "s3cr3t-api-key-value", "url": "https://generativelanguage.googleapis.com/v1/models?key=AIzaSyA1234567890abcdefghijklmnopqrstu"}}}"""

  "Redaction.safeBody" should "redact a bearer token, an api_key field and a key query parameter echoed in a body" in {
    val result = Redaction.safeBody(echoedBody)
    (result should not).include("sk-proj-abc123")
    (result should not).include("s3cr3t-api-key-value")
    (result should not).include("AIzaSyA1234567890")
    result should include("[REDACTED]")
    result should include("bad request")
  }

  it should "return a short clean body unchanged" in {
    Redaction.safeBody("""{"error": "model not found"}""") shouldBe """{"error": "model not found"}"""
  }

  it should "redact the whole body before cutting it, so a secret straddling the cut point does not survive" in {
    // OpenAI echoes a rejected key in full: "Incorrect API key provided: sk-proj-...".
    val key    = "sk-proj-" + ("abc123def456ghi789jk" * 2)
    val prefix = "Incorrect API key provided: "
    val body =
      prefix + key + ". You can find your API key at https://platform.openai.com/account/api-keys." + ("y" * 500)
    val cut = prefix.length + "sk-proj-".length + 19

    // Cutting first leaves 19 characters of the key, one short of what the key pattern needs, so they survive.
    Redaction.redact(Redaction.truncateForLog(body, cut)) should include("sk-proj-abc123def456ghi789j")

    val result = Redaction.safeBody(body, cut)
    (result should not).include("abc123def456")
    result should include("truncated")
  }

  it should "render a null body as null rather than throw" in {
    Redaction.safeBody(null) shouldBe "null"
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
