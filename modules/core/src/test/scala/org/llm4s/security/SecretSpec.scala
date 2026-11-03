package org.llm4s.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretSpec extends AnyFlatSpec with Matchers {

  "Secret" should "never show its value in toString" in {
    Secret("sk-live-0123456789abcdef").toString shouldBe "***"
    s"${Secret("sk-live-0123456789abcdef")}" shouldBe "***"
  }

  it should "reveal its value only when asked" in {
    Secret("sk-live-0123456789abcdef").reveal shouldBe "sk-live-0123456789abcdef"
  }

  it should "be equal by value and hash by value" in {
    Secret("sk-live-0123456789abcdef") shouldBe Secret("sk-live-0123456789abcdef")
    Secret("sk-live-0123456789abcdef").hashCode shouldBe Secret("sk-live-0123456789abcdef").hashCode
    Secret("sk-live-0123456789abcdef") should not be Secret("sk-live-fedcba9876543210")
  }

  it should "register itself, so its value is scrubbed from any text" in KnownSecrets.isolated {
    val s = Secret("sk-live-0123456789abcdef")
    KnownSecrets.scrub(
      s"""{"error":"bad key ${s.reveal}"}""",
      "[REDACTED]"
    ) shouldBe """{"error":"bad key [REDACTED]"}"""
  }

  it should "register the Basic header form" in KnownSecrets.isolated {
    Secret.basic("pk-lf-public-0001", "sk-lf-secret-0001")
    val header = java.util.Base64.getEncoder.encodeToString("pk-lf-public-0001:sk-lf-secret-0001".getBytes("UTF-8"))
    KnownSecrets.scrub(s"Authorization: Basic $header", "[REDACTED]") shouldBe "Authorization: Basic [REDACTED]"
  }

  it should "register a runtime token, which can then be forgotten" in KnownSecrets.isolated {
    val t = Secret.token("ya29.runtime-token-0123456789")
    t.toString shouldBe "***"
    t.reveal shouldBe "ya29.runtime-token-0123456789"
    KnownSecrets.scrub("Bearer ya29.runtime-token-0123456789", "[REDACTED]") shouldBe "Bearer [REDACTED]"
    KnownSecrets.forget(t.reveal)
    KnownSecrets.scrub("ya29.runtime-token-0123456789", "[REDACTED]") shouldBe "ya29.runtime-token-0123456789"
  }

  it should "read a null value as empty: never registered, and safe to hash and compare" in KnownSecrets.isolated {
    val configured = Secret(null)
    val token      = Secret.token(null)
    configured.reveal shouldBe ""
    token.reveal shouldBe ""
    configured.hashCode shouldBe Secret("").hashCode
    configured shouldBe token
    KnownSecrets.size shouldBe 0
  }

  it should "read a null Basic password as empty in the header too" in KnownSecrets.isolated {
    val header = Secret.basic("basic-user-0001", null).reveal
    new String(java.util.Base64.getDecoder.decode(header), "UTF-8") shouldBe "basic-user-0001:"
  }
}
