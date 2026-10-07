package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RedactionRemoteBodySpec extends AnyFlatSpec with Matchers {

  "Redaction.remoteBody" should "scrub every configured secret by exact match, URL-encoded and JSON-escaped" in {
    val secret  = "s3cr+t/\"value\"-1234"
    val encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8)
    val escaped = secret.replace("\"", "\\\"")
    val body    = s"""{"detail":"$escaped"} raw=$secret form=$encoded"""
    val out     = Redaction.remoteBody(body, Seq(secret))
    (out should not).include(secret)
    (out should not).include(encoded)
    (out should not).include("s3cr")
    out should include("[REDACTED]")
  }

  it should "redact OAuth credential fields by key, wherever their values recur" in {
    val body =
      """{"access_token":"at-123456789","refresh_token":"rt-123456789","id_token":"it-123456789",""" +
        """"client_secret":"cs-123456789","assertion":"as-123456789","subject_token":"st-123456789",""" +
        """"error_description":"token at-123456789 is not valid"}"""
    val out = Redaction.remoteBody(body)
    for value <- Seq("at-", "rt-", "it-", "cs-", "as-", "st-") do (out should not).include(value + "123456789")
    out should include("error_description")
  }

  it should "redact a field value containing escaped quotes completely" in {
    val out = Redaction.remoteBody("""{"access_token":"ab\"tail-part"}""")
    (out should not).include("tail-part")
  }

  it should "ignore blank secrets and truncate long bodies" in {
    val out = Redaction.remoteBody("y" * 5000, Seq("", "  "), maxLength = 100)
    out should startWith("y" * 100)
    out should include("truncated")
  }

  it should "not fail on a body containing replacement metacharacters" in {
    val body = """see ?api_key=$1\x and ?other=$2 and "token":"$3\\""""
    val out  = Redaction.remoteBody(body)
    out should include("?api_key=[REDACTED]")
    out should include("?other=$2")
    (out should not).include("$3")
  }

  "Redaction.scrubRemote" should "keep the body's JSON parseable for later extraction" in {
    val body = """{"error":{"message":"bad bearer opaque-bearer-99"},"access_token":"opaque-bearer-99"}"""
    val out  = Redaction.scrubRemote(body, Seq("opaque-bearer-99"))
    (out should not).include("opaque-bearer-99")
    ujson.read(out)("error")("message").str shouldBe "bad bearer [REDACTED]"
  }
}
