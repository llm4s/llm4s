package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `$` and `\` are special in a `Regex.replaceAllIn` replacement string: `$n` is a group reference and `\` escapes
 * the next character. Text from the input (a query parameter) and the caller's placeholder both reach a
 * replacement, so each must be quoted, or redaction throws, rewrites the text it should keep, or writes back the
 * credential it was asked to hide (#1655).
 */
class RedactionReplacementSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  // ---- query parameters whose key is not sensitive: kept exactly as they are ----

  private val keptQueries = Seq(
    "GET /search?q=\\",
    "GET /search?q=$5",
    "GET /search?q=$1",
    "GET /files?path=C:\\dir",
    "GET /files?path=C:\\dir&q=$0&page=2",
    "GET /x?a$1=b"
  )

  keptQueries.foreach { input =>
    "Redaction.redact" should s"keep the non-sensitive query parameter in '$input' unchanged" in {
      Redaction.redact(input) shouldBe input
    }

    "Redaction.redactForLogging" should s"keep the non-sensitive query parameter in '$input' unchanged" in {
      Redaction.redactForLogging(input) shouldBe input
    }
  }

  // ---- query parameters whose key is sensitive: the key is kept, the value redacted ----

  "Redaction.redact" should "keep a $ in a sensitive query key and redact its value" in {
    Redaction.redact("GET /x?api$key=abc") shouldBe s"GET /x?api$$key=$R"
  }

  it should "keep a \\ in a sensitive query key and redact its value" in {
    Redaction.redact("GET /x?my\\token=abc") shouldBe s"GET /x?my\\token=$R"
  }

  it should "redact a sensitive query value that holds $ and \\" in {
    Redaction.redact("GET /x?token=a$1\\b&q=$2") shouldBe s"GET /x?token=$R&q=$$2"
  }

  "Redaction.redactForLogging" should "keep a $ in a sensitive query key and redact its value" in {
    Redaction.redactForLogging("GET /x?api$key=abc") shouldBe s"GET /x?api$$key=$R"
  }

  // ---- a caller-supplied placeholder is written literally ----

  "Redaction.redact with a placeholder of $0" should "write it literally for a sensitive query value" in {
    Redaction.redact("GET /x?token=abc", placeholder = "$0") shouldBe "GET /x?token=$0"
  }

  it should "write it literally for an Authorization header" in {
    Redaction.redact("Authorization: Bearer abc", placeholder = "$0") shouldBe "Authorization: $0"
  }

  it should "write it literally for a JSON Authorization field" in {
    Redaction.redact("""{"Authorization": "Bearer abc"}""", placeholder = "$0") shouldBe """{"Authorization": "$0"}"""
  }

  it should "write it literally for a standalone Bearer token" in {
    Redaction.redact("sent Bearer abc.def", placeholder = "$0") shouldBe "sent $0"
  }

  it should "write it literally for a standalone Basic credential" in {
    Redaction.redact("sent Basic dXNlcjpwYXNz", placeholder = "$0") shouldBe "sent $0"
  }

  it should "write it literally for a known API key" in {
    Redaction.redact("Key: sk-proj-abc123def456ghi789jkl012mno345", placeholder = "$0") shouldBe "Key: $0"
  }

  "Redaction.redactForLogging with a placeholder of $0" should "write it literally" in {
    Redaction.redactForLogging("GET /x?token=abc", placeholder = "$0") shouldBe "GET /x?token=$0"
  }

  "Redaction.redact with a placeholder holding \\" should "write it literally" in {
    Redaction.redact("GET /x?token=abc", placeholder = "[RE\\DACTED]") shouldBe "GET /x?token=[RE\\DACTED]"
    Redaction.redact("sent Bearer abc", placeholder = "\\") shouldBe "sent \\"
  }

  "SecretPatterns.redactAllWithPlaceholder" should "write a placeholder of $0 literally" in {
    SecretPatterns.redactAllWithPlaceholder("Key: sk-proj-abc123def456ghi789jkl012mno345", "$0") shouldBe "Key: $0"
  }
}
