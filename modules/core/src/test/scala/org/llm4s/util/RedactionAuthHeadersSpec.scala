package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The credential-bearing HTTP headers beyond `Authorization` (#1686): `Proxy-Authorization`, `Cookie`, `Set-Cookie`
 * and the API-key and token headers, in every shape the redactor reads - a header line, a header after a prefix, a
 * JSON field and header map, JSON inside a string, a single-quoted dict, a container, `key=value` and a query
 * parameter. The values use schemes and formats the Bearer/Basic and provider-key patterns do not recognise, so only
 * the key can make them secret. Keys that merely start with one of these names are left alone.
 */
class RedactionAuthHeadersSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  // Values no other pattern recognises: a Digest or Negotiate credential, a cookie's session id.
  private val digest    = """Digest username="u", nonce="n0nce", response="6629fae49393a05397450978507c4ef1""""
  private val negotiate = "Negotiate YIIGhgYGKwYBBQUCoIIGejCCBnag"
  private val session   = "s3ss10nQ9zX"

  private def assertGone(out: String, secrets: String*): Unit =
    secrets.foreach(s => (out should not).include(s))

  // ---------------------------------------------------------------------------------------------
  // Header lines
  // ---------------------------------------------------------------------------------------------

  "Redaction.redact" should "redact a Proxy-Authorization header line whatever its scheme" in {
    Redaction.redact(s"Proxy-Authorization: $digest") shouldBe s"Proxy-Authorization: $R"
    Redaction.redact(s"proxy-authorization: $negotiate") shouldBe s"proxy-authorization: $R"
    Redaction.redact("Proxy-Authorization: AWS4-HMAC-SHA256 Credential=AKID/20261010, Signature=fe5f80f77d5f") shouldBe
      s"Proxy-Authorization: $R"
    Redaction.redact("Proxy-Authorization: rawSecretValue42") shouldBe s"Proxy-Authorization: $R"
  }

  it should "redact the whole value of a Cookie and a Set-Cookie header line" in {
    val in  = s"GET / HTTP/1.1\nCookie: theme=dark; sid=$session; csrftoken=c5rfQ\nAccept: */*"
    val out = Redaction.redact(in)
    out shouldBe s"GET / HTTP/1.1\nCookie: $R\nAccept: */*"
    val set = Redaction.redact(s"HTTP/1.1 200 OK\r\nSet-Cookie: sid=$session; Path=/; HttpOnly; Secure\r\nX: y")
    set shouldBe s"HTTP/1.1 200 OK\r\nSet-Cookie: $R\r\nX: y"
    Redaction.redact(s"set-cookie: sid=$session") shouldBe s"set-cookie: $R"
    Redaction.redact(s"COOKIE: sid=$session") shouldBe s"COOKIE: $R"
  }

  it should "redact a Cookie or Proxy-Authorization header written after a prefix" in {
    val out = Redaction.redact(s"request headers: Cookie: sid=$session; theme=dark")
    out shouldBe s"request headers: Cookie: $R"
    Redaction.redact(s"> Set-Cookie: sid=$session") shouldBe s"> Set-Cookie: $R"
    Redaction.redact(s"> Proxy-Authorization: $negotiate") shouldBe s"> Proxy-Authorization: $R"
  }

  it should "redact the API-key and token header lines" in {
    Seq("X-Api-Key", "Api-Key", "X-Goog-Api-Key", "X-Auth-Token", "X-Amz-Security-Token").foreach { header =>
      withClue(header) {
        Redaction.redact(s"$header: FwoGZXIvYXdzEJr7") shouldBe s"$header: $R"
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // JSON fields and header maps
  // ---------------------------------------------------------------------------------------------

  it should "redact each credential header of a JSON header map and keep the others" in {
    val headers = Seq(
      "Proxy-Authorization"  -> digest.replace("\"", "\\\""),
      "Cookie"               -> s"sid=$session; theme=dark",
      "Set-Cookie"           -> s"sid=$session; Path=/; HttpOnly",
      "X-Api-Key"            -> "k3yValue9",
      "Api-Key"              -> "k3yValue9",
      "X-Auth-Token"         -> "t0kenValue9",
      "X-Amz-Security-Token" -> "FwoGZXIvYXdzEJr7"
    )
    headers.foreach { case (name, value) =>
      withClue(name) {
        val in  = s"""{"headers": {"Content-Type": "application/json", "$name": "$value", "Accept": "*/*"}}"""
        val out = Redaction.redact(in)
        out shouldBe s"""{"headers": {"Content-Type": "application/json", "$name": "$R", "Accept": "*/*"}}"""
      }
    }
  }

  it should "redact a Proxy-Authorization JSON value that a Bearer or Basic pattern would not read whole" in {
    val out = Redaction.redact(s"""{"Proxy-Authorization": "$negotiate", "user": "ann"}""")
    out shouldBe s"""{"Proxy-Authorization": "$R", "user": "ann"}"""
    val escapedQuote = Redaction.redact("""{"proxy_authorization": "Basic dXNlcjpwYXNz\"QWXYZ"}""")
    escapedQuote shouldBe s"""{"proxy_authorization": "$R"}"""
  }

  it should "redact these headers in JSON that sits inside a string" in {
    Seq("Proxy-Authorization" -> negotiate, "Cookie" -> s"sid=$session", "Set-Cookie" -> s"sid=$session").foreach {
      case (name, value) =>
        withClue(name) {
          val in = s"""{"content": "headers: {\\"$name\\": \\"$value\\", \\"Accept\\": \\"*/*\\"}"}"""
          Redaction.redact(in) shouldBe
            s"""{"content": "headers: {\\"$name\\": \\"$R\\", \\"Accept\\": \\"*/*\\"}"}"""
        }
    }
  }

  it should "redact these headers in a single-quoted dict" in {
    val in  = s"""{'Proxy-Authorization': '$negotiate', 'Set-Cookie': 'sid=$session', 'Accept': '*/*'}"""
    val out = Redaction.redact(in)
    out shouldBe s"""{'Proxy-Authorization': '$R', 'Set-Cookie': '$R', 'Accept': '*/*'}"""
  }

  it should "redact every leaf of a cookie list" in {
    val in  = s"""{"cookies": [{"name": "sid", "value": "$session", "domain": "example.com"}], "n": 1}"""
    val out = Redaction.redact(in)
    assertGone(out, session, "example.com")
    out should endWith(""""n": 1}""")
    ujson.read(out)("cookies")(0)("value").str shouldBe R
  }

  // ---------------------------------------------------------------------------------------------
  // key=value and query parameters
  // ---------------------------------------------------------------------------------------------

  it should "redact these keys as key=value pairs" in {
    Redaction.redact(s"proxy_authorization=$session user=ann") shouldBe s"proxy_authorization=$R user=ann"
    Redaction.redact(s"cookie=$session user=ann") shouldBe s"cookie=$R user=ann"
    Redaction.redact(s"""set_cookie="sid=$session; Path=/" user=ann""") shouldBe s"""set_cookie="$R" user=ann"""
    Redaction.redact(s"x_amz_security_token=$session user=ann") shouldBe s"x_amz_security_token=$R user=ann"
  }

  it should "redact these keys as query parameters" in {
    Redaction.redact(s"https://h/p?proxy-authorization=$session&q=1") shouldBe s"https://h/p?proxy-authorization=$R&q=1"
    Redaction.redact(s"https://h/p?cookie=$session&q=1") shouldBe s"https://h/p?cookie=$R&q=1"
    Redaction.redact(s"https://h/p?X-Amz-Security-Token=$session&q=1") shouldBe
      s"https://h/p?X-Amz-Security-Token=$R&q=1"
  }

  // ---------------------------------------------------------------------------------------------
  // Near misses: names that only start with, or mention, one of these
  // ---------------------------------------------------------------------------------------------

  it should "leave keys that only start with cookie or authorization alone" in {
    val json =
      """{"cookie_policy": "strict", "cookie_consent": "granted", "max_cookie_age": "3600", "cookieDomain": "a.b",""" +
        """ "authorization_url": "https://auth.example/authorize", "preauthorization": "pending",""" +
        """ "security_level": "high"}"""
    Redaction.redact(json) shouldBe json
    val pairs = "cookie_policy=strict cookie_consent=granted authorization_url=https://a.example security_level=high"
    Redaction.redact(pairs) shouldBe pairs
    val lines = "Cookie-Policy: strict\nCookieConsent: granted\nMyCookie: chocolate"
    Redaction.redact(lines) shouldBe lines
    val prose = "We use cookies to improve the site. Cookie consent is optional."
    Redaction.redact(prose) shouldBe prose
  }

  // ---------------------------------------------------------------------------------------------
  // Cost
  // ---------------------------------------------------------------------------------------------

  it should "redact many such headers in time linear in the input" in {
    val units = Seq(
      "Cookie: a=b; c=d\n",
      "x Cookie:\n",
      s"""{"Proxy-Authorization": "$negotiate"}, """,
      "cookie_policy=strict&Cookie",
      "CookieCookieCookie:"
    )
    units.foreach(unit => LinearTime.assertLinear(s"unit $unit", unit * 5000, unit * 20000)(Redaction.redact(_)))
  }
}
