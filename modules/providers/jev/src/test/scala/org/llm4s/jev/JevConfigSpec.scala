package org.llm4s.jev

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class JevConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val secret = "tsk-test-key-123"

  private def refused(config: JevConfig): String =
    config.validate.left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "A config" should "default to TypeSafe's API, the jev-latest alias and the SDK's retry policy" in {
    val config = JevConfig(secret)

    config.baseUrl shouldBe "https://api.typesafe.ai"
    config.model shouldBe "jev-latest"
    config.timeout shouldBe 30.seconds
    config.retry shouldBe JevRetryPolicy.default
    config.headers shouldBe Map.empty
    config.evaluateUrl shouldBe "https://api.typesafe.ai/v1/systemone"
    config.validate shouldBe Right(config)
  }

  it should "build the evaluation URL from a base URL with or without a trailing slash" in {
    JevConfig(secret, baseUrl = "https://example.test/").evaluateUrl shouldBe "https://example.test/v1/systemone"
    JevConfig(secret, baseUrl = "https://example.test").evaluateUrl shouldBe "https://example.test/v1/systemone"
  }

  it should "change one setting at a time" in {
    val config = JevConfig(secret)
      .withApiKey("k2")
      .withBaseUrl("https://x.test")
      .withModel("jev-1.13.0")
      .withTimeout(5.seconds)
      .withRetry(JevRetryPolicy.none)
      .withHeaders(Map("X-A" -> "1"))

    config shouldBe JevConfig("k2", "https://x.test", "jev-1.13.0", 5.seconds, JevRetryPolicy.none, Map("X-A" -> "1"))
  }

  // ---- the credential is never shown ----

  it should "never print the API key, or the value of an extra header" in {
    val config = JevConfig(secret, headers = Map("X-Token" -> "header-secret"))

    config.toString should (include("apiKey=***")
      .and(not)
      .include(secret)
      .and(not)
      .include("header-secret")
      .and(include("X-Token")))
    config.toString should include("https://api.typesafe.ai")
  }

  // ---- the secret ----

  it should "refuse a blank key, and one TypeSafe's own SDKs refuse" in {
    refused(JevConfig("")) should include("blank")
    refused(JevConfig("   ")) should include("blank")
    refused(JevConfig("tsk with space")) should include("whitespace")
    refused(JevConfig("tsk\nX-Evil: 1")) should include("control character")
    refused(JevConfig("tsk\u0000")) should include("control character")
    refused(JevConfig("tsk-café")) should include("non-ASCII")
  }

  it should "not echo the key it refused" in {
    (refused(JevConfig("bad key with space")) should not).include("bad key")
  }

  // ---- the base URL: https, unless the host is loopback ----

  it should "accept https, in any case of the scheme, with a port or a path prefix" in {
    Seq("https://api.typesafe.ai", "HTTPS://api.typesafe.ai", "https://api.typesafe.ai:8443", "https://gw.example/jev")
      .foreach(url => JevConfig(secret, baseUrl = url).validate.isRight shouldBe true)
  }

  it should "accept http only for a loopback host" in {
    Seq("http://localhost:8080", "http://LOCALHOST", "http://127.0.0.1:9", "http://127.9.9.9", "http://[::1]:7")
      .foreach(url => JevConfig(secret, baseUrl = url).validate.isRight shouldBe true)
  }

  it should "refuse a plain-http URL for any other host, so the key is never sent in the clear" in {
    Seq(
      "http://api.typesafe.ai",
      "http://example.com",
      "http://10.0.0.1",
      "http://128.0.0.1",
      "http://127.0.0.1.evil.example",
      "http://localhost.evil.example",
      "http://evil-localhost",
      "http://0.0.0.0"
    ).foreach(url => withClue(url)(refused(JevConfig(secret, baseUrl = url)) should include("must be https")))
  }

  it should "refuse an out-of-range address that only looks like loopback" in {
    refused(JevConfig(secret, baseUrl = "http://127.0.0.256")) should (include("must be https").or(include("host")))
  }

  it should "refuse a URL whose userinfo dresses a bad host up as a good one" in {
    refused(JevConfig(secret, baseUrl = "http://localhost@evil.example/")) should include("credentials")
    refused(JevConfig(secret, baseUrl = "https://api.typesafe.ai@evil.example/")) should include("credentials")
    refused(JevConfig(secret, baseUrl = "https://user:pw@api.typesafe.ai")) should include("credentials")
  }

  it should "refuse a URL that is not an absolute http(s) URL with a host" in {
    refused(JevConfig(secret, baseUrl = "api.typesafe.ai")) should include("absolute URL")
    refused(JevConfig(secret, baseUrl = "ftp://api.typesafe.ai")) should include("must be https")
    refused(JevConfig(secret, baseUrl = "file:///etc/passwd")) should include("host")
    refused(JevConfig(secret, baseUrl = "https://")) should (include("valid URL").or(include("host")))
    refused(JevConfig(secret, baseUrl = "")) should include("host")
  }

  it should "refuse a URL with whitespace, a control character, a query or a fragment" in {
    refused(JevConfig(secret, baseUrl = "https://a.test/ x")) should include("whitespace or control")
    refused(JevConfig(secret, baseUrl = "https://a.test/\r\nHost: evil")) should include("whitespace or control")
    refused(JevConfig(secret, baseUrl = "https://a.test?x=1")) should include("query")
    refused(JevConfig(secret, baseUrl = "https://a.test#frag")) should include("fragment")
  }

  // ---- model, timeout, headers, retry ----

  it should "refuse a blank model and a timeout that is not positive" in {
    refused(JevConfig(secret, model = " ")) should include("llm4s.jev.model")
    refused(JevConfig(secret, timeout = Duration.Zero)) should include("llm4s.jev.timeout")
    refused(JevConfig(secret, timeout = -1.second)) should include("llm4s.jev.timeout")
  }

  it should "refuse a default header that could inject another or replace the client's own" in {
    refused(JevConfig(secret, headers = Map("X-A" -> "1\r\nEvil: 1"))) should include("llm4s.jev.headers")
    refused(JevConfig(secret, headers = Map("Authorization" -> "Bearer x"))) should include("cannot be overridden")
  }

  "A retry policy" should "default to TypeSafe's SDK values" in {
    JevRetryPolicy.default shouldBe JevRetryPolicy(2, 500.millis, 5.seconds, 0.25, 30.seconds)
    JevRetryPolicy.none.maxRetries shouldBe 0
    JevRetryPolicy.default.validate shouldBe Right(JevRetryPolicy.default)
  }

  it should "change one setting at a time" in {
    val policy = JevRetryPolicy.default
      .withMaxRetries(4)
      .withBackoffInitial(1.second)
      .withBackoffMax(8.seconds)
      .withJitter(0.5)
      .withBudget(1.minute)

    policy shouldBe JevRetryPolicy(4, 1.second, 8.seconds, 0.5, 1.minute)
  }

  it should "refuse settings that cannot work, naming each" in {
    def refusedPolicy(p: JevRetryPolicy): String = JevConfig(secret, retry = p).validate.left.value.message

    refusedPolicy(JevRetryPolicy(maxRetries = -1)) should include("maxRetries")
    refusedPolicy(JevRetryPolicy(maxRetries = JevRetryPolicy.MaxRetriesLimit + 1)) should include("maxRetries")
    refusedPolicy(JevRetryPolicy(backoffInitial = -1.second)) should include("backoffInitial")
    refusedPolicy(JevRetryPolicy(backoffInitial = 2.seconds, backoffMax = 1.second)) should include("backoffMax")
    refusedPolicy(JevRetryPolicy(jitter = 1.5)) should include("jitter")
    refusedPolicy(JevRetryPolicy(jitter = -0.1)) should include("jitter")
    refusedPolicy(JevRetryPolicy(jitter = Double.NaN)) should include("jitter")
    refusedPolicy(JevRetryPolicy(budget = Duration.Zero)) should include("budget")
  }
}
