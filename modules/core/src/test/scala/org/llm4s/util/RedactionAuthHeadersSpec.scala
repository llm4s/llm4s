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
    val key = s"""{"Cookie: sid=$session": 1, "k": "keep"}"""
    Redaction.redact(key) shouldBe s"""{"Cookie: $R": 1, "k": "keep"}"""
    val beforeNumber = s"""["Cookie: sid=$session", 1, "Cookie: sid=$session", null]"""
    Redaction.redact(beforeNumber) shouldBe s"""["Cookie: $R", 1, "Cookie: $R", null]"""
    val inArrayString = s"""["[\\"debug Cookie: sid=$session; lang=en\\",1]",1]"""
    Redaction.redact(inArrayString) shouldBe s"""["[\\"debug Cookie: $R",1]"""
    Seq(array, multiline, curl, nested, last, key, beforeNumber, inArrayString).foreach(in =>
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

  it should "read a cookie value to the end of its line where the first quote in it does not end a JSON string" in {
    // A quote opened earlier on the line, unescaped quotes in the value: not well-formed JSON, so no later quote is
    // searched for and every cookie after the first quote is replaced too, the quotes with it.
    Redaction.redact(s"""x="a Cookie: p={"t":"d"}; sid=$session"""") shouldBe s"""x="a Cookie: $R"""
    Redaction.redact(s"""5" disk Cookie: sid="abc", "$session"""") shouldBe s"""5" disk Cookie: $R"""
    Redaction.redact(s"""msg="Set-Cookie: a="x", 2fa=$session; Path=/""") shouldBe s"""msg="Set-Cookie: $R"""
    Redaction.redact(s"""log ["Cookie: a="b"] sid=$session""") shouldBe s"""log ["Cookie: $R"""
    Redaction.redact(s"""size 5" Cookie: prefs="{"}" sid=$session""") shouldBe s"""size 5" Cookie: $R"""
    Redaction.redact(s"""msg="Cookie: a="x", "$session"""") shouldBe s"""msg="Cookie: $R"""
    // A quote and a closing bracket before a word end no JSON string, wherever the quote is in the value.
    Redaction.redact(s"""msg="req Cookie: x="}$session; y=z""") shouldBe s"""msg="req Cookie: $R"""
    Redaction.redact(s"""msg="req Cookie: "}$session""") shouldBe s"""msg="req Cookie: $R"""
    Redaction.redact(s"""msg="req Cookie: "} $session\nX: keep""") shouldBe s"""msg="req Cookie: $R\nX: keep"""
    // Well-formed JSON still ends the value at its string's end, an empty value is still left as it is.
    val empty = """{"h":"Cookie: "}"""
    Redaction.redact(empty) shouldBe empty
    val jsonl = s"""{"h":["Cookie: sid=$session"]}\n{"h":["Cookie: sid=$session"]}\n"""
    Redaction.redact(jsonl) shouldBe s"""{"h":["Cookie: $R"]}\n{"h":["Cookie: $R"]}\n"""
    val pretty = s"""{\n  "h": [\n    "Cookie: sid=$session"\n  ],\n  "n": 1\n}"""
    Redaction.redact(pretty) shouldBe s"""{\n  "h": [\n    "Cookie: $R"\n  ],\n  "n": 1\n}"""
    Seq(empty, pretty).foreach(in => assertJson(Redaction.redact(in)))
  }

  it should "still redact the fields after a cookie value that takes the quotes of its line" in {
    // The cookie pass runs after every other pass, so a value that takes the quote closing the `key="` value around
    // the header changes no text a field pass reads: the `key="value"` pass and the dict passes pair the quotes as
    // they would were cookies not redacted, and a field on a later line keeps its value redacted. Each case is one a
    // review found leaking when the cookie pass ran first.
    Redaction.redact(s"""level=info msg="Cookie: theme="dark" sid=$session"\npassword="SEKH1"""") shouldBe
      s"""level=info msg="Cookie: $R\npassword="$R""""
    val threeLines = s"""a=1\nmsg="req Cookie: t="d"; sid=$session" ok=1\nb=2 secret="SEKH9" c=3"""
    Redaction.redact(threeLines) shouldBe s"""a=1\nmsg="req Cookie: $R\nb=2 secret="$R" c=3"""
    Redaction.redact(s"""msg="Cookie: a="b$session\nconfig password="SEKD5" x""") shouldBe
      s"""msg="Cookie: $R\nconfig password="$R" x"""
    Redaction.redact(s"""a" msg="Cookie: x=" y\npassword="SEKH2"""") shouldBe s"""a" msg="Cookie: $R\npassword="$R""""
    Redaction.redact(s"""x="hi\nCookie: a="b$session\npassword="SEKH3"""") shouldBe s"""x="hi\nCookie: $R\npassword="$R""""
    Redaction.redact(s"""Cookie: sid="$session"\npassword="SEKH8"""") shouldBe s"""Cookie: $R\npassword="$R""""
    // A string closed before the header, quoted cookie values after it, a Python repr on the next line.
    val closedBefore =
      s"""ts=1 level=info msg="GET /account" Cookie: theme="dark"; sid=$session\nts=2 cfg={'password': "hunter2", 'user': 'bob'}"""
    Redaction.redact(closedBefore) shouldBe
      s"""ts=1 level=info msg="GET /account" Cookie: $R\nts=2 cfg={'password': "$R", 'user': 'bob'}"""
    val dictAfter = Seq(
      s"""a="x" Cookie: b="c" d\n{'password': "SEKA1"}""",
      s"""a="x" Cookie: b="c" d\ntoken="SEKA2" z""",
      s"""a="x" Cookie: b="c" d\n{'token': ['SEKA4', 'SEKA5']}""",
      s"""msg="Cookie: b="c" e="f\n{'password': "SEKB1"}""",
      s"""a="x" Cookie: b="c" d\na="x" Cookie: b="c" d\n{'password': "SEKB4"}""",
      s"""req="GET /" Cookie: theme="dark"; lang=en\n{'api_key': "SEKE2"}""",
      s"""upstream="1" Set-Cookie: sid="abc"; Path=/\nheaders={'Authorization': "Bearer SEKE3x", 'X-Api-Key': "SEKE4"}""",
      s"""{"msg":"Set-Cookie: session=\\"abc\\"; lang=x","n":1}  cookie: session="dark" csrftoken=y csrftoken="\r\n  "auth_token": "SEKE5","""
    )
    dictAfter.foreach(in => assertGone(Redaction.redact(in), "SEK"))
  }

  // ---------------------------------------------------------------------------------------------
  // The cookie pass only adds: what is redacted without it stays redacted
  // ---------------------------------------------------------------------------------------------

  /** The input with every `Cookie:` read as no header: one letter changed, so no quote, key or line moves. */
  private def withoutCookieHeaders(in: String): String =
    "(?i)(cooki)(e:)".r.replaceAllIn(in, m => java.util.regex.Matcher.quoteReplacement(m.group(1) + "x:"))

  it should "leave redacted every secret that is redacted when no cookie header is read" in {
    // Log lines with cookie headers in every quote context - a string open or closed before the header, quoted and
    // unbalanced cookie values, escaped quotes - followed by fields under main's keys in logfmt, JSON and Python repr
    // shapes. Reading the cookie headers must only add to what is redacted (#1686): every secret the passes redact
    // when no cookie header is read stays redacted. Deterministic.
    val rnd       = new scala.util.Random(1686)
    var id        = 0
    def secret(): String = { id += 1; f"Zq$id%05dWx" }
    def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    val prefixes = Seq(
      "",
      "msg=\"",
      "a=\"x\" ",
      "ts=1 level=info msg=\"GET /account\" ",
      "level=info msg=\"",
      "log \"",
      "{\"msg\":\"",
      "\"",
      "'",
      "size 5\" ",
      "x=\\\"y ",
      "<34>Oct 10 13:00:00 host app[42]: ",
      "10.0.0.1 - - [10/Oct/2026:13:00:00 +0000] \"GET / HTTP/1.1\" 200 5 \"-\" \"",
      "it's "
    )
    val headers = Seq("Cookie: ", "Set-Cookie: ", "cookie:", "COOKIE:\t", "Set-Cookie:  ")
    val atoms = Seq(
      "theme=\"dark\"",
      "sid=abc",
      "a=\"b",
      "\"",
      "\\\"",
      "\\\\\"",
      "; ",
      ", ",
      " ",
      "x y",
      "'",
      "\"}",
      "]",
      "p={\"a\":1}",
      "\" ok=1"
    )
    val secretFields: Seq[String => String] = Seq(
      s => s"""password="$s"""",
      s => s"""token="$s" z""",
      s => s"""{'password': "$s"}""",
      s => s"""{'password': '$s', 'n': 1}""",
      s => s"""{'token': ['$s', 2]}""",
      s => s"""{"token": ["$s"]}""",
      s => s"""{"password": "$s"}""",
      s => s"""access_token=$s""",
      s => s"""cfg={'api_key': "$s", 'a': 'b'}""",
      s => s"""secret = "$s"""",
      s => s"""  "auth_token": "$s",""",
      s => s"""x-api-key: $s"""
    )
    val noise     = Seq("k=\"v\"", "\"", "it's", "\\\"", "ok", "{'a': 'b'}")
    val breaks    = Seq("\n", "\r\n", "\n\n", " ")
    var redacted  = 0
    var leaks     = Vector.empty[String]
    (1 to 2000).foreach { _ =>
      val lines = Vector.fill(2 + rnd.nextInt(4)) {
        rnd.nextInt(3) match {
          case 0 =>
            pick(prefixes) + pick(headers) + Vector.fill(1 + rnd.nextInt(5))(pick(atoms)).mkString +
              pick(Seq("", "\"", "\" ok=1", "'", "\"}"))
          case 1 => pick(secretFields)(secret())
          case _ => pick(noise)
        }
      }
      val in      = lines.reduce((a, b) => a + pick(breaks) + b)
      val secrets = "Zq[0-9]{5}Wx".r.findAllIn(in).toVector
      val without = Redaction.redact(withoutCookieHeaders(in))
      val out     = Redaction.redact(in)
      secrets.filterNot(without.contains).foreach { s =>
        redacted += 1
        if (out.contains(s)) leaks :+= s"$s in: $in\nout: $out"
      }
    }
    withClue(leaks.take(5).mkString("\n\n"))(leaks shouldBe empty)
    redacted should be > 1000
  }

  it should "end a cookie value at an escaped escape of a line break only where the next header follows it" in {
    // An odd count of \" before the header is no proof of JSON inside JSON: the run of two is a backslash and a letter.
    val prose = s"""{"log":"he said \\"Cookie: sid=a\\\\n$session","n":1}"""
    Redaction.redact(prose) shouldBe s"""{"log":"he said \\"Cookie: $R","n":1}"""
    // In JSON inside JSON, the next header after the escaped escape still ends the value.
    val nested = s"""{"o":"{\\"l\\":\\"Cookie: sid=$session\\\\nX: keep\\"}","k":"keep"}"""
    Redaction.redact(nested) shouldBe s"""{"o":"{\\"l\\":\\"Cookie: $R\\\\nX: keep\\"}","k":"keep"}"""
    Seq(prose, nested).foreach(in => assertJson(Redaction.redact(in)))
  }

  it should "read a cookie header that follows any JSON escape" in {
    Seq("\\f", "\\b", "\\u0009", "\\u003e", "\\u003E").foreach { escape =>
      withClue(escape) {
        val in = s"""{"log":"x${escape}Cookie: sid=$session","n":1}"""
        Redaction.redact(in) shouldBe s"""{"log":"x${escape}Cookie: $R","n":1}"""
      }
    }
    // A backslash and a letter that starts no JSON escape: `Cookie` there continues a word.
    Seq("\\N", "\\X", "\\x").foreach { notEscape =>
      withClue(notEscape) {
        val in = s"""{"log":"x${notEscape}Cookie: sid=$session","n":1}"""
        Redaction.redact(in) shouldBe in
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
      "plain, no line break"       -> (n => "Cookie: " + "a=b; " * n),
      "in a string, never closed"  -> (n => open + "a=b; " * n),
      "backslash runs"             -> (n => open + """\\\\n\\""" * n),
      "escaped quotes"             -> (n => open + """\" , [x""" * n),
      "quotes before a word"       -> (n => open + ("\"" + " " * 20 + "," + " " * 20 + "x") * n),
      "quotes and commas"          -> (n => open + """" , x""" * n),
      "string in a string"         -> (n => """{"o":"{\"l\":\"Cookie: """ + """\\\\n\"x""" * n),
      "a quote open on each line"  -> (n => "\"Cookie: a\"b\n" * n),
      "escaped escapes, no header" -> (n => """{"o":"\"Cookie: """ + """\\nX\\r\\nY""" * n),
      "closing brackets"           -> (n => open + "\"" + "} ] " * n + "x"),
      "a stray quote on each line" -> (n => "5\" Cookie: a=\"b\"; c=d\n" * n)
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(5000), build(20000))(Redaction.redact(_))
    }
  }
}
