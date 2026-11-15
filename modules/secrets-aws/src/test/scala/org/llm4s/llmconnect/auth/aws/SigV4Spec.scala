package org.llm4s.llmconnect.auth.aws

import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Instant

/** [[SigV4]] against the signatures AWS publishes for its Signature Version 4 examples. */
class SigV4Spec extends AnyFlatSpec with Matchers {

  private val example = AwsCredentials("AKIDEXAMPLE", Secret("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"))
  private val at      = Instant.parse("2015-08-30T12:36:00Z")

  "SigV4" should "sign AWS's get-vanilla example as AWS does" in {
    SigV4.signatureOf(
      "GET",
      URI.create("https://example.amazonaws.com/"),
      Map("X-Amz-Date" -> "20150830T123600Z"),
      Array.emptyByteArray,
      example,
      "us-east-1",
      "service",
      at
    ) shouldBe "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
  }

  it should "sign AWS's IAM ListUsers example, with a sorted query and a header value with spaces, as AWS does" in {
    SigV4.signatureOf(
      "GET",
      URI.create("https://iam.amazonaws.com/?Version=2010-05-08&Action=ListUsers"),
      Map("Content-Type" -> "application/x-www-form-urlencoded;  charset=utf-8", "X-Amz-Date" -> "20150830T123600Z"),
      Array.emptyByteArray,
      example,
      "us-east-1",
      "iam",
      at
    ) shouldBe "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
  }

  it should "add the date, the session token and an Authorization header naming the scope and signed headers" in {
    val signed = SigV4.sign(
      "GET",
      URI.create("https://example.amazonaws.com"),
      Map.empty,
      Array.emptyByteArray,
      example.withSessionToken(Secret("session-token-example")),
      "us-east-1",
      "service",
      at
    )
    signed("X-Amz-Date") shouldBe "20150830T123600Z"
    signed("X-Amz-Security-Token") shouldBe "session-token-example"
    signed("Authorization") should startWith(
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature="
    )
    (signed("Authorization") should not).include("wJalrXUtnFEMI")
  }

  it should "sign the host with its port only when the port is not the scheme's default" in {
    SigV4.hostOf(URI.create("https://secretsmanager.us-east-1.amazonaws.com:443/")) shouldBe
      "secretsmanager.us-east-1.amazonaws.com"
    SigV4.hostOf(URI.create("http://localhost:80/")) shouldBe "localhost"
    SigV4.hostOf(URI.create("http://localhost:4566/")) shouldBe "localhost:4566"
  }
}
