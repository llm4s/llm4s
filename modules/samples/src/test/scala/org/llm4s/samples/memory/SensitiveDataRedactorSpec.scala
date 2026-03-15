package org.llm4s.samples.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SensitiveDataRedactorSpec extends AnyFlatSpec with Matchers {

  "SensitiveDataRedactor" should "redact API keys and bearer tokens" in {
    val text     = "apiKey=sk-12345678901234567890 and Bearer abc.def.ghi"
    val redacted = SensitiveDataRedactor.redact(text)

    (redacted should not).include("sk-12345678901234567890")
    redacted should include("apiKey=***REDACTED***")
    redacted should include("Bearer ***REDACTED***")
  }

  it should "redact password-like fields" in {
    val text     = "password=secret123 pwd: hunter2"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("password=***REDACTED***")
    redacted should include("pwd=***REDACTED***")
  }

  it should "redact emails" in {
    val text     = "Contact me at user@example.com for details"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("***@***.***")
    (redacted should not).include("user@example.com")
  }

  it should "redact tokens and credentials" in {
    val text     = "token=abc123 credential: xyz789"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("token=***REDACTED***")
    redacted should include("credential=***REDACTED***")
  }

  it should "redact credit card numbers" in {
    val text     = "Card 4111 1111 1111 1111 was used"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("****-****-****-****")
    (redacted should not).include("4111 1111 1111 1111")
  }

  it should "redact phone numbers" in {
    val text     = "Call 555-123-4567 or 555 987 6543"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("***-***-****")
  }

  it should "redact SSNs" in {
    val text     = "SSN 123-45-6789"
    val redacted = SensitiveDataRedactor.redact(text)

    redacted should include("***-**-****")
    (redacted should not).include("123-45-6789")
  }

  it should "leave non-sensitive text unchanged" in {
    val text     = "This is safe public content."
    val redacted = SensitiveDataRedactor.redact(text)

    redacted shouldBe text
  }
}
