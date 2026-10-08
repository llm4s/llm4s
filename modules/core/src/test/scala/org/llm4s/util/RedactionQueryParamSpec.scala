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

  it should "redact a sensitive parameter of a URL nested in the value of a parameter that is kept" in {
    Redaction.redact("GET /login?next=/cb?token=SECRETAA&x=1") shouldBe s"GET /login?next=/cb?token=$R&x=1"
  }

  it should "redact a query string with no URL before it" in {
    Redaction.redact("?api_key=SECRETAA&user=ann") shouldBe s"?api_key=$R&user=ann"
  }
}
