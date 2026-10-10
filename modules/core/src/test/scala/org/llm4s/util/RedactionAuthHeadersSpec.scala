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
  // Where a cookie header's value ends
  // ---------------------------------------------------------------------------------------------

  private def assertJson(out: String): Unit =
    withClue(out)(noException should be thrownBy ujson.read(out))

  it should "end a cookie header's value at an escaped line break in a JSON string" in {
    val crlf = s"""{"log":"GET / HTTP/1.1\\r\\nCookie: sid=$session; x=y\\r\\nAccept: */*\\r\\n","next":"keepme"}"""
    Redaction.redact(crlf) shouldBe
      s"""{"log":"GET / HTTP/1.1\\r\\nCookie: $R\\r\\nAccept: */*\\r\\n","next":"keepme"}"""
    val lf = s"""{"log":"x\\nCookie: sid=$session\\nAccept: keep","n":1}"""
    Redaction.redact(lf) shouldBe s"""{"log":"x\\nCookie: $R\\nAccept: keep","n":1}"""
    val set = s"""{"log":"HTTP/1.1 200 OK\\nSet-Cookie: sid=$session; Path=/\\nX: keep","n":1}"""
    Redaction.redact(set) shouldBe s"""{"log":"HTTP/1.1 200 OK\\nSet-Cookie: $R\\nX: keep","n":1}"""
    val unicode = s"""{"log":"x\\u000aCookie: sid=$session\\u000dAccept: keep","n":1}"""
    Redaction.redact(unicode) shouldBe s"""{"log":"x\\u000aCookie: $R\\u000dAccept: keep","n":1}"""
    Seq(crlf, lf, set, unicode).foreach(in => assertJson(Redaction.redact(in)))
  }

  it should "end a cookie header's value at an escaped line break in JSON inside a JSON string" in {
    val in  = s"""{"outer":"{\\"log\\":\\"Cookie: sid=$session\\\\r\\\\nAccept: x\\"}","k":"keep"}"""
    val out = Redaction.redact(in)
    out shouldBe s"""{"outer":"{\\"log\\":\\"Cookie: $R\\\\r\\\\nAccept: x\\"}","k":"keep"}"""
    ujson.read(ujson.read(out)("outer").str)("log").str shouldBe s"Cookie: $R\r\nAccept: x"
  }

  it should "end a cookie header's value where the JSON string around it ends" in {
    val array = s"""["Cookie: sid=$session; t=1", "Accept: */*", "X: keep"]"""
    Redaction.redact(array) shouldBe s"""["Cookie: $R", "Accept: */*", "X: keep"]"""
    val multiline = s"""[\n  "Cookie: sid=$session",\n  "Accept: keep"\n]"""
    Redaction.redact(multiline) shouldBe s"""[\n  "Cookie: $R",\n  "Accept: keep"\n]"""
    val curl = s"""{"request":"curl -H 'Cookie: sid=$session' https://x","status":200}"""
    Redaction.redact(curl) shouldBe s"""{"request":"curl -H 'Cookie: $R","status":200}"""
    val nested = s"""{"outer":"{\\"log\\":\\"Cookie: sid=$session\\"}","k":"keep"}"""
    Redaction.redact(nested) shouldBe s"""{"outer":"{\\"log\\":\\"Cookie: $R","k":"keep"}"""
    val last = s"""{"headers":["Accept: */*","Set-Cookie: sid=$session"]}"""
    Redaction.redact(last) shouldBe s"""{"headers":["Accept: */*","Set-Cookie: $R"]}"""
    val beforeNumber = s"""["Cookie: sid=$session", 1, "Cookie: sid=$session", null]"""
    Redaction.redact(beforeNumber) shouldBe s"""["Cookie: $R", 1, "Cookie: $R", null]"""
    val inArrayString = s"""["[\\"debug Cookie: sid=$session; lang=en\\",1]",1]"""
    Redaction.redact(inArrayString) shouldBe s"""["[\\"debug Cookie: $R",1]"""
    Seq(array, multiline, curl, nested, last, beforeNumber, inArrayString).foreach(in =>
      assertJson(Redaction.redact(in))
    )
  }

  it should "redact the whole of a quoted or backslashed cookie value inside a JSON string" in {
    val quoted = s"""{"log":"Cookie: sid=\\"$session\\"; x=y","n":1}"""
    Redaction.redact(quoted) shouldBe s"""{"log":"Cookie: $R","n":1}"""
    // An even run of backslashes and `n` is an escaped backslash and a letter, not a line break.
    val backslash = s"""{"log":"Cookie: a=b\\\\n$session","n":1}"""
    Redaction.redact(backslash) shouldBe s"""{"log":"Cookie: $R","n":1}"""
    val afterBreak = s"""{"log":"Cookie:\\n sid=$session","n":1}"""
    Redaction.redact(afterBreak) shouldBe s"""{"log":"Cookie:\\n $R","n":1}"""
    val python = s"""{'log': 'Cookie: sid=$session\\r\\nAccept: x', 'k': 'keep'}"""
    Redaction.redact(python) shouldBe s"""{'log': 'Cookie: $R\\r\\nAccept: x', 'k': 'keep'}"""
    // After the escape of a quote, as HTML-safe serialisers write it.
    val unicodeQuote = s"""{"o":"\\u0022Cookie: sid=$session\\u0022","k":"keep"}"""
    Redaction.redact(unicodeQuote) shouldBe s"""{"o":"\\u0022Cookie: $R","k":"keep"}"""
  }

  it should "read a cookie header outside a JSON string to the end of its line, quotes and all" in {
    Redaction.redact(s"""Cookie: sid="$session"; x=y""") shouldBe s"Cookie: $R"
    Redaction.redact(s"""Set-Cookie: a="x", b="$session"\nX: keep""") shouldBe s"Set-Cookie: $R\nX: keep"
    // A quote that opened on the line, around the header, unescaped: its quoted values do not end it.
    Redaction.redact(s"""msg="Set-Cookie: a="x", b="$session"; Path=/" user=ann""") shouldBe s"""msg="Set-Cookie: $R"""
    Redaction.redact(s"""log "Cookie: sid="$session"; x=y" done""") shouldBe s"""log "Cookie: $R"""
    // A quote left open on an earlier line is not a string around the header.
    Redaction.redact(s"""say "hi\nCookie: a="x", b="$session"""") shouldBe s"""say "hi\nCookie: $R"""
    // Backslashes outside a string are part of the value.
    Redaction.redact(s"""Cookie: a=b\\r\\n$session""") shouldBe s"Cookie: $R"
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
      "CookieCookieCookie:",
      """{"log":"Cookie: a\r\nCookie: b\"c\"", "x": 1}, """,
      """["Cookie: x", "y"], "Cookie:\n\r\t """ + "\\u000a\"\n"
    )
    units.foreach(unit => LinearTime.assertLinear(s"unit $unit", unit * 5000, unit * 20000)(Redaction.redact(_)))
  }

  it should "read one long cookie value in time linear in its length" in {
    // One value each, which nothing ends early: no line break, quotes that end no string, runs of backslashes.
    val open = """{"log":"Cookie: """
    val shapes: Seq[(String, Int => String)] = Seq(
      "plain, no line break"      -> (n => "Cookie: " + "a=b; " * n),
      "in a string, never closed" -> (n => open + "a=b; " * n),
      "backslash runs"            -> (n => open + """\\\\n\\""" * n),
      "escaped quotes"            -> (n => open + """\" , [x""" * n),
      "quotes before a word"      -> (n => open + ("\"" + " " * 20 + "," + " " * 20 + "x") * n),
      "quotes and commas"         -> (n => open + """" , x""" * n),
      "string in a string"        -> (n => """{"o":"{\"l\":\"Cookie: """ + """\\\\n\"x""" * n),
      "a quote open on each line" -> (n => "\"Cookie: a\"b\n" * n)
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(5000), build(20000))(Redaction.redact(_))
    }
  }
}
