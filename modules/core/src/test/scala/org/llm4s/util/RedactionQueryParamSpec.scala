package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * A query parameter exists only inside a URL or a query string. The pass that redacts a sensitive one used to read a
 * key from any `?` or `&` up to the next `=` anywhere in the input, so a `?` in prose - a question in a chat message -
 * started a "parameter" whose key ran across quotes, braces and lines to a later `=`. When that span held a sensitive
 * word, the pass rewrote the JSON after it, and the field passes after it, reading the mangled text, left credentials
 * readable (#1667).
 */
class RedactionQueryParamSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  // ---------------------------------------------------------------------------------------------
  // A '?' in prose: the document keeps its structure, and every credential is redacted
  // ---------------------------------------------------------------------------------------------

  "Redaction.redact" should "redact every credential after a question in a chat message (#1667)" in {
    val input =
      """{"messages": [{"role": "user", "content": "Is this right?"}], "credentials": {"dsn": "postgres://u@h/db?sslmode=require", "password": "hunter2}SECRET", "keys": ["SECRETBB"]}}"""
    val out = Redaction.redact(input)
    out shouldBe
      s"""{"messages": [{"role": "user", "content": "Is this right?"}], "credentials": {"dsn": "$R", "password": "$R", "keys": ["$R"]}}"""
    parses(out) shouldBe true
  }

  it should "redact every credential after a question in a pretty-printed document" in {
    val input =
      """{
        |  "messages": [
        |    {"role": "user", "content": "Is this right?"}
        |  ],
        |  "credentials": {
        |    "dsn": "postgres://u@h/db?sslmode=require",
        |    "password": "hunter2}SECRET",
        |    "keys": ["SECRETBB"]
        |  }
        |}""".stripMargin
    val out = Redaction.redact(input)
    out shouldBe
      s"""{
         |  "messages": [
         |    {"role": "user", "content": "Is this right?"}
         |  ],
         |  "credentials": {
         |    "dsn": "$R",
         |    "password": "$R",
         |    "keys": ["$R"]
         |  }
         |}""".stripMargin
    parses(out) shouldBe true
  }

  it should "keep a later URL's non-sensitive query after a question" in {
    val input = """{"content": "Is this right?", "token": "SECRETAA", "url": "https://x.test/cb?code=abc"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"content": "Is this right?", "token": "$R", "url": "https://x.test/cb?code=abc"}"""
    parses(out) shouldBe true
  }

  it should "redact a later URL's sensitive query value after a question, and keep the rest" in {
    val input =
      """{"content": "Can you check this? See below.", "url": "https://api.test/v1?api_key=SECRETAA&user=ann", "n": 1}"""
    val out = Redaction.redact(input)
    out shouldBe
      s"""{"content": "Can you check this? See below.", "url": "https://api.test/v1?api_key=$R&user=ann", "n": 1}"""
    parses(out) shouldBe true
  }

  it should "keep an '=' inside a later string value after a question" in {
    val input = """{"q": "Why?", "password": "hunter2", "note": "a=b"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"q": "Why?", "password": "$R", "note": "a=b"}"""
    parses(out) shouldBe true
  }

  it should "keep a question mark in a JSON key and the fields after it" in {
    val input = """{"what?": "x", "api_key": "SECRETAA", "expr": "n=1"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"what?": "x", "api_key": "$R", "expr": "n=1"}"""
    parses(out) shouldBe true
  }

  it should "keep a document with a question on one line and an '=' on another" in {
    val input = "Is this right?\nmodel=gpt-4o\nanswer: yes"
    Redaction.redact(input) shouldBe input
  }

  it should "still redact a sensitive key=value pair after a question in prose" in {
    Redaction.redact("Really? token=SECRETAA and more") shouldBe s"Really? token=$R and more"
  }

  // ---------------------------------------------------------------------------------------------
  // A URL's query: a sensitive value is redacted in every shape, and the text around the URL is kept
  // ---------------------------------------------------------------------------------------------

  it should "redact the sensitive parameters of a query and keep the others" in {
    Redaction.redact("GET https://x.test/v1?api_key=SECRETAA&user=ann&token=SECRETBB&access_token=SECRETCC") shouldBe
      s"GET https://x.test/v1?api_key=$R&user=ann&token=$R&access_token=$R"
  }

  it should "redact a sensitive query value that ends a JSON string, and keep the closing quote" in {
    val out = Redaction.redact("""{"url": "https://x.test/v1?token=SECRETAA", "n": 1}""")
    out shouldBe s"""{"url": "https://x.test/v1?token=$R", "n": 1}"""
    parses(out) shouldBe true
  }

  it should "redact a sensitive query value that ends a single-quoted string, and keep the closing quote" in {
    Redaction.redact("{'url': 'https://x.test/v1?token=SECRETAA', 'n': 1}") shouldBe
      s"{'url': 'https://x.test/v1?token=$R', 'n': 1}"
  }

  it should "redact a sensitive query value in JSON that sits inside a string, and keep the fields after it" in {
    val out = Redaction.redact("""{"c": "{\"url\": \"https://x.test/?monkey=SECRETAA\", \"n\": \"a=b\"}"}""")
    out shouldBe s"""{"c": "{\\"url\\": \\"https://x.test/?monkey=$R\\", \\"n\\": \\"a=b\\"}"}"""
  }

  it should "redact a sensitive query value under a bracketed key" in {
    Redaction.redact("https://x.test/?filter[api_key]=SECRETAA&page=2") shouldBe
      s"https://x.test/?filter[api_key]=$R&page=2"
  }

  it should "redact a sensitive query value after an HTML-escaped '&'" in {
    Redaction.redact("""<a href="https://x.test/?a=1&amp;token=SECRETAA">""") shouldBe
      s"""<a href="https://x.test/?a=1&amp;token=$R">"""
  }

  it should "redact percent-encoded, '+' and '/' characters of a sensitive query value" in {
    Redaction.redact("https://x.test/?token=ab%2Fcd+ef/gh==&x=1") shouldBe s"https://x.test/?token=$R&x=1"
  }

  it should "redact a sensitive query value in a logfmt line" in {
    Redaction.redact("""level=info url="https://x.test/v1?api_key=SECRETAA" status=200""") shouldBe
      s"""level=info url="https://x.test/v1?api_key=$R" status=200"""
  }

  it should "leave an empty sensitive query value, and the quote after it, as they are" in {
    val input = """{"url": "https://x.test/?monkey=", "n": 1}"""
    Redaction.redact(input) shouldBe input
  }

  it should "redact a quoted query value inside its quotes" in {
    Redaction.redact("set ?monkey='SECRETAA' and ?monkey=\"SECRETBB\" here") shouldBe
      s"set ?monkey='$R' and ?monkey=\"$R\" here"
  }

  it should "redact a sensitive query value with a quote inside it, which a query may hold unencoded" in {
    Redaction.redact("GET /x?monkey=ab'cd&x=1 and ?monkey=ef\"gh ok") shouldBe s"GET /x?monkey=$R&x=1 and ?monkey=$R ok"
  }

  it should "redact the whole of a sensitive query value with a quote before a sub-delimiter RFC 3986 allows" in {
    Redaction.redact("GET https://h/login?user=bob&password=pa'(ss)w0rd&x=1") shouldBe
      s"GET https://h/login?user=bob&password=$R&x=1"
    Redaction.redact("https://h/login?password=Xk9'!mQ2zR") shouldBe s"https://h/login?password=$R"
    Redaction.redact("?token=ab'*cd") shouldBe s"?token=$R"
    Redaction.redact("?token=ab'@cd") shouldBe s"?token=$R"
    Redaction.redact("?token=ab'=cd") shouldBe s"?token=$R"
    Redaction.redact("?token=ab'$cd") shouldBe s"?token=$R"
  }

  it should "redact the whole of a sensitive query value in a JSON string with a quote before a sub-delimiter" in {
    val out = Redaction.redact("""{"url": "https://h/login?password=Xk9'$mQ2zR"}""")
    out shouldBe s"""{"url": "https://h/login?password=$R"}"""
    parses(out) shouldBe true
  }

  it should "redact the whole of a sensitive query value with a run of quotes inside it or at its start" in {
    Redaction.redact("?token=ab''cd") shouldBe s"?token=$R"
    Redaction.redact("?password=''Xk9mQ2") shouldBe s"?password=$R"
  }

  it should "keep the quotes that end a string after a value with a run of quotes, and an empty quoted value" in {
    Redaction.redact("{'url': 'https://h/x?token=ab''', 'n': 1}") shouldBe s"{'url': 'https://h/x?token=$R''', 'n': 1}"
    Redaction.redact("set ?token='' here") shouldBe "set ?token='' here"
  }

  it should "keep the quote and the text after it when a quote that ends a string closes a value" in {
    Redaction.redact("fetch('https://h/x?token=SECRETAA')") shouldBe s"fetch('https://h/x?token=$R')"
    Redaction.redact("INSERT INTO t VALUES ('https://h/x?token=SECRETAA');") shouldBe
      s"INSERT INTO t VALUES ('https://h/x?token=$R');"
    Redaction.redact("{'url': 'https://h/x?token=SECRETAA', 'n': 1}") shouldBe
      s"{'url': 'https://h/x?token=$R', 'n': 1}"
    Redaction.redact("{'url': 'https://h/x?token=SECRETAA': 1}") shouldBe s"{'url': 'https://h/x?token=$R': 1}"
  }

  // The stated exception: a quote before `,`, `)`, `;` or `:` cannot be told from the quote that ends a string
  // (`fetch('...?token=ab')`), so a value that holds one is redacted only up to it, and the rest is written.
  it should "redact a sensitive query value only up to a quote before ',', ')', ';' or ':' (stated exception)" in {
    Redaction.redact("?token=ab',cd") shouldBe s"?token=$R',cd"
    Redaction.redact("?token=ab')cd") shouldBe s"?token=$R')cd"
    Redaction.redact("?token=ab';cd") shouldBe s"?token=$R';cd"
    Redaction.redact("?token=ab':cd") shouldBe s"?token=$R':cd"
  }

  it should "redact a sensitive parameter of a URL nested in the value of a parameter that is kept" in {
    Redaction.redact("GET /login?next=/cb?token=SECRETAA&x=1") shouldBe s"GET /login?next=/cb?token=$R&x=1"
  }

  it should "redact a query string with no URL before it" in {
    Redaction.redact("?api_key=SECRETAA&user=ann") shouldBe s"?api_key=$R&user=ann"
  }

  // ---------------------------------------------------------------------------------------------
  // JSON-escaped separators: Go's encoding/json writes '&' as its six-character escape, backslash and u0026, and
  // HTML-safe serialisers also escape '=' (u003d) and '?' (u003f). The escapes separate a query as the characters do,
  // and the output keeps them as they were written (#1676).
  // ---------------------------------------------------------------------------------------------

  /**
   * `~26`, `~3d` and `~3f` stand for the JSON escapes of `&`, `=` and `?` (a backslash, `u00` and the hex digits).
   * Scala reads a backslash-u sequence in any string literal, triple-quoted ones too, as the character it encodes, so
   * the tests write the escapes this way.
   */
  private def u(s: String): String = s.replace("~", "\\" + "u00")

  it should "redact a sensitive query parameter after a JSON-escaped '&' (#1676)" in {
    val out = Redaction.redact(u("""{"url":"https://h/x?a=1~26token=SECRETPW"}"""))
    out shouldBe u(raw"""{"url":"https://h/x?a=1~26token=$R"}""")
    parses(out) shouldBe true
    ujson.read(out)("url").str shouldBe s"https://h/x?a=1&token=$R"
  }

  it should "redact a sensitive parameter first, in the middle and last of an escaped query, and keep the others" in {
    Redaction.redact(u("""{"url":"https://h/x?token=SECRETAA~26a=1~26b=2"}""")) shouldBe
      u(raw"""{"url":"https://h/x?token=$R~26a=1~26b=2"}""")
    Redaction.redact(u("""{"url":"https://h/x?a=1~26api_key=SECRETAA~26b=2"}""")) shouldBe
      u(raw"""{"url":"https://h/x?a=1~26api_key=$R~26b=2"}""")
    Redaction.redact(u("""{"url":"https://h/x?a=1~26b=2~26password=SECRETAA"}""")) shouldBe
      u(raw"""{"url":"https://h/x?a=1~26b=2~26password=$R"}""")
  }

  it should "redact every sensitive parameter of an escaped query" in {
    Redaction.redact(u("""{"url":"https://h/x?token=SECRETAA~26user=ann~26access_token=SECRETBB~26n=2"}""")) shouldBe
      u(raw"""{"url":"https://h/x?token=$R~26user=ann~26access_token=$R~26n=2"}""")
  }

  it should "leave an escaped query with no sensitive parameter, and an escaped '&' in prose, as they are" in {
    val input = u("""{"url":"https://h/x?a=1~26page=2~26model=gpt-4o","note":"fish ~26 chips"}""")
    Redaction.redact(input) shouldBe input
  }

  it should "read an escaped '=' between a key and its value, and an escaped '?' where a query starts" in {
    Redaction.redact(u("""{"url":"https://h/x?a=1~26token~3dSECRETAA~26b=2"}""")) shouldBe
      u(raw"""{"url":"https://h/x?a=1~26token~3d$R~26b=2"}""")
    Redaction.redact(u("""{"url":"https://h/x~3ftoken=SECRETAA"}""")) shouldBe
      u(raw"""{"url":"https://h/x~3ftoken=$R"}""")
    Redaction.redact(u("""{"url":"https://h/x~3fa~3d1~26api_key~3dSECRETAA~26b~3d2"}""")) shouldBe
      u(raw"""{"url":"https://h/x~3fa~3d1~26api_key~3d$R~26b~3d2"}""")
  }

  it should "read an escaped '=' after a quote inside a sensitive value as it reads '='" in {
    // After the quote, an escaped '&' no longer ends the value either: the quotes of a value are not paired
    Redaction.redact(u("""{"url":"https://h/x?a=1~26password=ab'~3dcdSECRET~26b=2"}""")) shouldBe
      u(raw"""{"url":"https://h/x?a=1~26password=$R"}""")
  }

  it should "read escaped '?' and '=' with upper-case hex digits, which JSON allows" in {
    Redaction.redact(u("""{"url":"https://h/x~3Fa=1~26token~3DSECRETAA"}""")) shouldBe
      u(raw"""{"url":"https://h/x~3Fa=1~26token~3D$R"}""")
  }

  it should "redact a sensitive parameter after a double-escaped '&' in JSON that sits inside a string" in {
    val out = Redaction.redact(u("""{"body":"{\"url\":\"https://h/x?a=1\~26token=SECRETAA\~26b=2\",\"n\":1}"}"""))
    out shouldBe u(raw"""{"body":"{\"url\":\"https://h/x?a=1\~26token=$R\~26b=2\",\"n\":1}"}""")
    parses(out) shouldBe true
    ujson.read(ujson.read(out)("body").str)("url").str shouldBe s"https://h/x?a=1&token=$R&b=2"
  }

  it should "redact a sensitive parameter after an escaped '&' outside JSON, in a log line" in {
    Redaction.redact(u("GET https://h/x?a=1~26secret=SECRETAA~26b=2 200")) shouldBe
      u(s"GET https://h/x?a=1~26secret=$R~26b=2 200")
  }

  it should "redact a quoted key=value field after an escaped '&', as after '&'" in {
    Redaction.redact(u("""{"form":"user=ann~26password='pa ss'~26n=1"}""")) shouldBe
      u(raw"""{"form":"user=ann~26password='$R'~26n=1"}""")
    Redaction.redact(u("""user=ann~26password="pa ss"~26n=1""")) shouldBe u(s"""user=ann~26password="$R"~26n=1""")
  }

  it should "still read a key that runs over an escape up to an unescaped '=', as the pass before #1676 did" in {
    Redaction.redact(u("?a~3dtoken=SECRETAA")) shouldBe u(s"?a~3dtoken=$R")
    Redaction.redact(u("?token~26a=SECRETAA")) shouldBe u(s"?token~26a=$R")
  }

  it should "read a backslash that starts no separator escape as part of a sensitive value" in {
    Redaction.redact(u("""?token=ab~41cd\\xy&n=1""")) shouldBe s"?token=$R&n=1"
  }

  // ---------------------------------------------------------------------------------------------
  // A credential that holds '&': Go's encoding/json, logrus and System.Text.Json escape it too, and the value of a
  // sensitive key=value pair ends at the escape only where another pair follows it (#1676)
  // ---------------------------------------------------------------------------------------------

  it should "redact a key=value credential holding an escaped '&' in full, in a DSN, a sentence, an env line and a command" in {
    Redaction.redact(u("""{"dsn":"host=db user=app password=p~26ssW0rdQZXJ dbname=x"}""")) shouldBe
      s"""{"dsn":"host=db user=app password=$R dbname=x"}"""
    Redaction.redact(u("""{"msg":"connecting with password=Tr0ub~264dorQZXJ to db"}""")) shouldBe
      s"""{"msg":"connecting with password=$R to db"}"""
    Redaction.redact(u("""{"env":"DB_PASSWORD=ab~26cdQZXJ"}""")) shouldBe s"""{"env":"DB_PASSWORD=$R"}"""
    Redaction.redact(u("""{"cmd":"psql 'host=db password=p~26ssQZXJ'"}""")) shouldBe
      s"""{"cmd":"psql 'host=db password=$R'"}"""
  }

  it should "end a sensitive key=value value at an escaped '&' that another pair follows, with '=' or its escape" in {
    Redaction.redact(u("""{"form":"password=SECRETQZ~26user=bob"}""")) shouldBe
      u(raw"""{"form":"password=$R~26user=bob"}""")
    Redaction.redact(u("""{"form":"password=SECRETQZ~26user~3dbob"}""")) shouldBe
      u(raw"""{"form":"password=$R~26user~3dbob"}""")
  }

  it should "redact a key=value credential in escaped quotes in full, whatever pairs it seems to hold" in {
    Redaction.redact(u("""{"msg":"login DB_PASSWORD=~27aUFQs~26$n~26ZScH85~27 user=bob"}""")) shouldBe
      s"""{"msg":"login DB_PASSWORD=$R user=bob"}"""
    Redaction.redact(u("""{"msg":"login password=~27p~26user=x~27 n=1"}""")) shouldBe
      s"""{"msg":"login password=$R n=1"}"""
    Redaction.redact(u("""{"msg":"login password=~22p~26user=x~22 n=1"}""")) shouldBe
      s"""{"msg":"login password=$R n=1"}"""
    Redaction.redact(u("""{"msg":"login password=~27pQZXJ~27~26user=bob"}""")) shouldBe
      u(raw"""{"msg":"login password=$R~26user=bob"}""")
  }

  // ---------------------------------------------------------------------------------------------
  // A query value that holds a quote: System.Text.Json escapes ', ", & (and + < >), so a credential written in quotes
  // that holds '&' arrives with every one of them escaped. An escaped '&' after a quote does not end the value.
  // ---------------------------------------------------------------------------------------------

  it should "redact a quoted query credential that holds '&' in full, as System.Text.Json writes it" in {
    // `GET https://h/x?password='p&ss=QZXJ'` serialised by System.Text.Json's default encoder
    val out = Redaction.redact(u("""{"msg":"GET https://h/x?password=~27p~26ss=QZXJ~27"}"""))
    out shouldBe s"""{"msg":"GET https://h/x?password=$R"}"""
    parses(out) shouldBe true
    Redaction.redact(u("""{"msg":"GET https://h/x?a=1~26password=~27p~26ss=QZXJ~27"}""")) shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26password=$R"}""")
  }

  it should "redact a quoted query credential that holds '&' in full, after '?', after '&' and with '\"'" in {
    Redaction.redact(u("x=1~26password=~27p~26ss=QZXJ~27 n=1")) shouldBe u(s"x=1~26password=$R n=1")
    Redaction.redact(u("x=1&password=~27p~26ss=QZXJ~27 n=1")) shouldBe s"x=1&password=$R n=1"
    Redaction.redact(u("https://h/p?password=~27p~26ss=QZXJ~27 n=1")) shouldBe s"https://h/p?password=$R n=1"
    Redaction.redact(u("q?password=~27p~26ss=QZXJ~27 n=1")) shouldBe s"q?password=$R n=1"
    Redaction.redact(u("x=1~26password=~22p~26ss=QZXJ~22 n=1")) shouldBe u(s"x=1~26password=$R n=1")
    Redaction.redact(u("a~26b;password=~27p~26ss=QZXJ~27;n=1")) shouldBe u(s"a~26b;password=$R")
    Redaction.redact(u("a;password=~27p~26ss=QZXJ~27;n=1")) shouldBe s"a;password=$R;n=1"
  }

  it should "redact a quoted query credential that holds several escaped '&' in full" in {
    Redaction.redact(
      u("""{"msg":"GET https://h/x?a=1~26password=~27p~26ss=QZXJ~26tt=WXYZ~26uu=KLMN~27 n=1"}""")
    ) shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26password=$R n=1"}""")
    Redaction.redact(u("""{"msg":"GET https://h/x?password=~22p~26ss=QZXJ~26tt=WXYZ~22"}""")) shouldBe
      s"""{"msg":"GET https://h/x?password=$R"}"""
  }

  it should "run a query value over an escaped '&' after a bare quote, as before #1676" in {
    Redaction.redact(u("""{"msg":"GET https://h/x?password='p~26ss=QZXJ'"}""")) shouldBe
      s"""{"msg":"GET https://h/x?password='$R'"}"""
    Redaction.redact(u("?password=ab'cd~26ss=QZXJ n=1")) shouldBe s"?password=$R n=1"
  }

  it should "redact a key=value credential in escaped quotes up to its closing quote, over ';' and spaces" in {
    Redaction.redact(u("""{"msg":"GET https://h/x?a=1~26tt=2;client_secret=~27KZYZ;TTLGFR17504~27"}""")) shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26tt=2;client_secret=$R"}""")
    Redaction.redact(u("""{"msg":"login password=~27pa ss,wo;rd~27 user=bob"}""")) shouldBe
      s"""{"msg":"login password=$R user=bob"}"""
    Redaction.redact(u("""{"msg":"note=~27hi there~27 password=SECRETQZ"}""")) shouldBe
      u(raw"""{"msg":"note=~27hi there~27 password=$R"}""")
  }

  it should "read a key=value pair whose '=' is escaped, as Gson writes it" in {
    Redaction.redact(u("""{"msg":"x;password~3dSECRETQZ n=1"}""")) shouldBe
      u(raw"""{"msg":"x;password~3d$R n=1"}""")
    Redaction.redact(u("x;password~3d'SECRET QZ' n=1")) shouldBe u(s"x;password~3d'$R' n=1")
  }

  it should "read a quoted credential holding escaped '&' in time linear in its length" in {
    // Each value is read in one forward scan: no search for a matching quote starts again from each character, which
    // would make four times the input take sixteen times as long.
    val shapes = Seq(
      ("?password=~27", "a~26b=1;c,d", ""),
      ("x;password=~27", "a b;c,d&e~26f=", ""),
      ("", "?token=~27a~26b~27", ""),
      ("", "x;password=~27a;b~27 ", ""),
      ("", "~26password~3d~22a~26b ", ""),
      ("?token=ab'", "c~26d", "")
    ).map { case (prefix, unit, suffix) => (u(prefix), u(unit), u(suffix)) }
    shapes.foreach { case (prefix, unit, suffix) =>
      def time(repeats: Int): Long = {
        val input = prefix + (unit * repeats) + suffix
        Redaction.redact(input) // warm up
        val start = System.nanoTime()
        Redaction.redact(input)
        System.nanoTime() - start
      }
      val small = time(5000)
      val large = time(20000)
      withClue(s"unit $unit: 5000 took ${small / 1000000} ms, 20000 took ${large / 1000000} ms: ") {
        large should be < (small * 12 + 50000000L)
      }
    }
  }

  it should "read a Bearer token only after the escape of a separator, whose 'u' is lower-case in JSON" in {
    Redaction.redact(u("~26Bearer zqxjv")) shouldBe u(s"~26$R")
    Redaction.redact(u("x~26Basic zqxjv")) shouldBe u(s"x~26$R")
    Redaction.redact("\\U0026Bearer zqxjv") shouldBe "\\U0026Bearer zqxjv"
    Redaction.redact("\\U0026Bearer password=zqxjv") shouldBe s"\\U0026Bearer password=$R"
  }
}
