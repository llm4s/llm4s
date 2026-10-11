package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class RedactionRemoteBodySpec extends AnyFlatSpec with Matchers {
  import RedactionRemoteBodySpec._

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

  it should "redact the RFC 8693 credentials in every shape the general passes read" in {
    val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzcGlmZmU6Ly9leGFtcGxlIn0.c2lnbmF0dXJlLXZhbHVl"
    val shapes = Seq(
      // The exchange's own form body, as a transport might log it.
      s"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=$jwt" +
        "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt&client_assertion=ca-opaque-value-1",
      s"https://idp.example/token?subject_token=$jwt&actor_token=ac-opaque-value-1",
      s"""{"subject_token":"$jwt","client_assertion":"ca-opaque-value-1"}""",
      s"""{"body":"{\\"assertion\\":\\"ca-opaque-value-1\\",\\"subject_token\\":\\"$jwt\\"}"}""",
      s"""{'subject_token': '$jwt', 'assertion': 'ca-opaque-value-1'}""",
      "Assertion: ca-opaque-value-1",
      s"actor_token=ac-opaque-value-1 subject_token=$jwt"
    )
    for shape <- shapes do
      val out = Redaction.redact(shape)
      withClue(s"$shape -> $out: ") {
        (out should not).include("ca-opaque-value-1")
        (out should not).include("ac-opaque-value-1")
        (out should not).include("c2lnbmF0dXJlLXZhbHVl")
      }
    // `subject_token_type` and `grant_type` name token types, not credentials: they stay readable.
    Redaction.redact("subject_token_type=urn:ietf:params:oauth:token-type:jwt") should include("token-type:jwt")
  }

  // #1818: the bare `assertion` key carries an RFC 7521 / 7523 grant. Its value is redacted in every shape unless it
  // holds a space, as a test framework's assertion does and no grant does.
  it should "redact a grant under a bare assertion key in every shape, by redact and remoteBody (#1818)" in {
    for {
      (kind, value) <- grants
      key           <- Seq("assertion", "Assertion", "ASSERTION")
      (shape, line) <- assertionShapes(key, value)
    } {
      val probe = value.substring(8, 24)
      withClue(s"$kind under $key, $shape: ") {
        (Redaction.redact(line) should not).include(probe)
        (Redaction.remoteBody(line, maxLength = 0) should not).include(probe)
      }
    }
  }

  it should "redact a base64 SAML assertion wrapped with escaped line breaks under a bare assertion key (#1818)" in {
    val wrapped = grants.head._2.grouped(24).mkString("\\r\\n")
    for line <- Seq(s"""{"assertion":"$wrapped"}""", s"""{'assertion': '$wrapped'}""", s"Assertion: $wrapped") do
      withClue(line)((Redaction.redact(line) should not).include(grants.head._2.substring(24, 40)))
  }

  it should "leave a test framework's assertion under a bare assertion key readable: it holds a space (#1818)" in {
    val benign = Seq(
      "Assertion: expected status 200 but got 404",
      "assertion: response.status == 200",
      """{"assertion": "response.status == 200", "passed": false}""",
      """{"error":"test failed","assertion":"expected 3 items"}""",
      """{'assertion': 'len(items) > 0'}""",
      """{"body":"{\"assertion\":\"status == 200\"}"}""",
      """ASSERTION="body contains welcome""""
    )
    for line <- benign do
      withClue(line) {
        Redaction.redact(line) shouldBe line
        Redaction.remoteBody(line, maxLength = 0) shouldBe line
      }
  }

  it should "still redact what the key does not decide inside a prose assertion, and every compound key (#1818)" in {
    val jwt = grants.last._2
    // A JWT, a Bearer token or a sensitive pair inside the prose is read by its own pattern.
    for line <- Seq(
        s"""{"assertion": "token $jwt was rejected"}""",
        "Assertion: expected Bearer abcdefghijklmnop to be refused",
        """{'assertion': 'login with password=hunter2xyz fails'}"""
      )
    do
      withClue(line) {
        val out = Redaction.redact(line)
        (out should not).include("c2lnbmF0dXJlLXZhbHVl")
        (out should not).include("abcdefghijklmnop")
        (out should not).include("hunter2xyz")
      }
    // A container under the bare key, and a compound key, are redacted whatever they hold.
    for line <- Seq(
        """{"assertion": {"value": "secret grant here"}}""",
        """{"assertion": ["secret grant here"]}""",
        """{"client_assertion": "secret grant here"}""",
        """{'saml_assertion': 'secret grant here'}""",
        "Client-Assertion: secret grant here"
      )
    do withClue(line)((Redaction.redact(line) should not).include("secret grant here"))
    // The type of an assertion and a count are no credentials.
    Redaction.redact("https://x.example/?client_assertion_type=urn:jwt-bearer&a=1") should include("urn:jwt-bearer")
    Redaction.redact("https://x.example/?assertion_count=3") should include("assertion_count=3")
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

object RedactionRemoteBodySpec {

  val samlXml =
    """<saml2:Assertion xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion" ID="_a1b2c3"><saml2:Subject>svc""" +
      """</saml2:Subject><ds:Signature>MIIC9zCCAd+gAwIBAgIJAKx</ds:Signature></saml2:Assertion>"""
  val grants: Seq[(String, String)] = Seq(
    "base64 SAML" -> java.util.Base64.getEncoder.encodeToString(samlXml.getBytes(StandardCharsets.UTF_8)),
    "base64url SAML" -> java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(samlXml.getBytes(StandardCharsets.UTF_8)),
    "opaque" -> "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8w",
    "hex"    -> "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "JWT"    -> "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzcGlmZmU6Ly9leGFtcGxlIn0.c2lnbmF0dXJlLXZhbHVl"
  )

  def assertionShapes(key: String, value: String): Seq[(String, String)] = Seq(
    "JSON field"         -> s"""{"$key":"$value"}""",
    "JSON field, spaced" -> s"""{"$key": "$value", "x": 1}""",
    "IdP error echo"     -> s"""{"error":"invalid_grant","error_description":"bad grant","$key":"$value"}""",
    "Python dict"        -> s"""{'$key': '$value'}""",
    "escaped JSON"       -> s"""{"body":"{\\"$key\\":\\"$value\\"}"}""",
    "header line"        -> s"$key: $value",
    "nested object"      -> s"""{"$key":{"value":"$value"}}""",
    "array"              -> s"""{"$key":["$value"]}""",
    "quoted pair"        -> s"""$key="$value"""",
    "pair"               -> s"$key=$value",
    "form body"          -> s"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Asaml2-bearer&$key=$value",
    "query"              -> s"https://idp.example/token?$key=$value&x=1"
  )
}
