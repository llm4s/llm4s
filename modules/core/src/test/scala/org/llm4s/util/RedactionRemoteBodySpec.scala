package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class RedactionRemoteBodySpec extends AnyFlatSpec with Matchers {

  /**
   * Runs `body` on a thread with a deliberately small (256 KB) stack and returns its value; fails the test if it
   * threw anything, a `StackOverflowError` included - which `Try` would not catch.
   */
  private def onSmallStack[A](body: => A): A = {
    val result  = new AtomicReference[Option[A]](None)
    val failure = new AtomicReference[Option[Throwable]](None)
    val thread  = new Thread(null, () => result.set(Some(body)), "small-stack", 256L * 1024)
    thread.setUncaughtExceptionHandler((_, e) => failure.set(Some(e)))
    thread.start()
    thread.join(60000)
    failure.get.foreach(e => fail(s"threw on a 256 KB stack: $e"))
    result.get.getOrElse(fail("did not finish"))
  }

  private val Depth  = 100000
  private val Secret = "opaque-bearer-deep-1"
  private val deepBody =
    s"""{"error":{"message":"bad bearer $Secret"},"nested":""" + "[" * Depth + s""""$Secret"""" + "]" * Depth + "}"
  private val deepArray = "[" * Depth + s""""$Secret"""" + "]" * Depth

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

  it should "redact the RFC 8693 request fields by key, however short their values" in {
    // Values under eight characters are left to the key patterns of `redact`, not scrubbed as repeats.
    val body =
      """{"subject_token":"st-1","actor_token":"ac-1","assertion":"as-1","client_assertion":"ca-1",""" +
        """"subjectToken":"st-2","grant_type":"urn:ietf:params:oauth:grant-type:token-exchange"}"""
    val out = Redaction.remoteBody(body)
    for value <- Seq("st-1", "ac-1", "as-1", "ca-1", "st-2") do (out should not).include(value)
    out should include("urn:ietf:params:oauth:grant-type:token-exchange")
    val pairs = Redaction.redact("subject_token=st-3&actor_token=ac-3")
    (pairs should not).include("st-3")
    (pairs should not).include("ac-3")
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

  "Redaction.scrubRemote and remoteBody" should "return for a body nested 100,000 deep on a small stack, never repeating a secret" in {
    for body <- Seq(deepBody, deepArray) do {
      val scrubbed = onSmallStack(Redaction.scrubRemote(body, Seq(Secret)))
      val remote   = onSmallStack(Redaction.remoteBody(body, Seq(Secret)))
      (scrubbed should not).include(Secret)
      (remote should not).include(Secret)
      scrubbed.length should be > Depth
    }
  }

  it should "still find a sensitive JSON value in a body nested within the bound" in {
    val body = """{"message":"echo opaque-access-tok-9","x":""" + "[" * 400 +
      """{"access_token":"opaque-access-tok-9"}""" + "]" * 400 + "}"
    val out = onSmallStack(Redaction.scrubRemote(body))
    (out should not).include("opaque-access-tok-9")
  }

  "Redaction.identifiers" should "keep identifiers of eight characters or more and drop shorter ones" in {
    Redaction.identifiers(Seq("app", "prod", " ab12345 ", "sp-client-4d2e81", null, "")) shouldBe
      Seq("sp-client-4d2e81")
    Redaction.identifiers(Seq("org_1234")) shouldBe Seq("org_1234")
  }

  it should "leave a body readable when a short identifier occurs in it, and scrub a long one" in {
    val body = """{"error":{"message":"app prod rejected for client sp-client-4d2e81"}}"""
    val out  = Redaction.remoteBody(body, Redaction.identifiers(Seq("app", "prod", "sp-client-4d2e81")))
    out should include("app prod rejected")
    (out should not).include("sp-client-4d2e81")
  }

  it should "never weaken the scrub of a credential passed as a secret, however short" in {
    val out = Redaction.remoteBody("token k9x echoed", Seq("k9x"))
    (out should not).include("k9x")
  }
}
