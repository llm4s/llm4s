package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * The shape issues that were open against the regex passes when `Redaction` became one forward pass over a tokenizer
 * (#1932), each with its issue's reproducers: what each now gives, idempotent, and JSON still JSON.
 */
class RedactionOpenIssuesSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** `~27` and the like stand for a JSON escape (a backslash, `u00` and the hex digits). */
  private def u(s: String): String = s.replace("~", "\\" + "u00")

  /** Redacts `input`, checks that a second redaction changes nothing and that JSON stays JSON, and returns it. */
  private def redacted(input: String): String = {
    val out = Redaction.redact(input)
    withClue(s"redacting the output for $input again: ")(Redaction.redact(out) shouldBe out)
    if (parses(input)) withClue(s"output $out: ")(parses(out) shouldBe true)
    out
  }

  "Redaction.redact" should "redact a plural credential key in every shape (#1646)" in {
    redacted("""{"secrets": ["hunter2value"]}""") shouldBe s"""{"secrets": ["$R"]}"""
    redacted("""{"api_keys": ["hunter2value", "hunter3value"]}""") shouldBe s"""{"api_keys": ["$R", "$R"]}"""
    redacted("secrets=hunter2value") shouldBe s"secrets=$R"
  }

  it should "keep prose that mentions 'token': [, inside a string and outside one, and be idempotent (#1657)" in {
    val inString = """{"content":"The 'token': [ field isn't documented. Thanks, it's urgent!"}"""
    redacted(inString) shouldBe inString
    val outside = "note: see 'token': [ for details. Content-Type: application/json, model gpt-4o-mini"
    redacted(outside) shouldBe outside
    // a backslash before a bare word keeps the escaped character, so the quotes after it pair as they did
    redacted("""{"token": [\a1, "x"], "password": "QZXJVK"}""") shouldBe
      s"""{"token": [\\a"$R", "$R"], "password": "$R"}"""
  }

  it should "show of a cut body's prefix nothing that redacting the whole body hides (#1680)" in {
    // RedactionSafeBodySpec holds the property; here, the issue's counterexample: a key cut by a fixed-length prefix
    // would be too short for its pattern, so the prefix is cut at a separator and redacted as a cut text.
    val key  = "sk-proj-" + "abc123def456ghi789jk" * 2
    val body = s"""{"password": "${"p" * 9000}"} """ + "word " * 7000 + key + " tail" + ("z " * 70000)
    (Redaction.safeBody(body, 2048) should not).include("abc123def456")
  }

  it should "not let a container a JSON string mentions rewrite a later key=value (#1689)" in {
    redacted("""{"msg": "the \"token\": [ field; login password=hunter2secret ok"}""") shouldBe
      s"""{"msg": "the \\"token\\": [ field; login password=$R ok"}"""
    redacted("""{"a": "\"token\": [ password=ab12CDEF"}""") shouldBe s"""{"a": "\\"token\\": [ password=$R"}"""
    redacted("""{"a": "\"token\": [ password=s3cr3tXYZ"}""") shouldBe s"""{"a": "\\"token\\": [ password=$R"}"""
  }

  it should "not let an escaped-quote query value swallow the key of the field after it (#1752)" in {
    // The value opened by the escape of `'` runs to the end of the input, the field after it with it.
    redacted(u("""?token=~27:"password":Basic client_secretZ107x3K9mQ""")) shouldBe u(s"?token=~27$R")
    redacted(u("""?token=~27:"password":Basic abc_Z107x3K9mQ""")) shouldBe u(s"?token=~27$R")
  }

  it should "redact a key after a JSON-escaped line break, and key=\\\"value\\\" in plain text (#1765)" in {
    redacted("""{"config":"[server]\nTOKEN=s3cr3tvalue\nport=8080"}""") shouldBe
      s"""{"config":"[server]\\nTOKEN=$R\\nport=8080"}"""
    // depth 2, and the YAML and dotenv forms
    val depth2 = ujson.Obj("o" -> ujson.Obj("config" -> "[server]\nTOKEN=s3cr3tvalue\nport=8080").render()).render()
    redacted(depth2) shouldBe s"""{"o":"{\\"config\\":\\"[server]\\\\nTOKEN=$R\\\\nport=8080\\"}"}"""
    redacted(ujson.Obj("config" -> "db:\n  password: s3cr3tvalue\n  port: 5432").render()) shouldBe
      s"""{"config":"db:\\n  password: $R\\n  port: 5432"}"""
    redacted(ujson.Obj("env" -> "API_KEY=s3cr3tvalue\r\nDEBUG=1").render()) shouldBe
      s"""{"env":"API_KEY=$R\\r\\nDEBUG=1"}"""
    redacted("""login token=\"s3cr3t value\" ok""") shouldBe s"""login token=\\"$R\\" ok"""
  }

  it should "not let a masked value's stray quote change how a later Python repr pairs its quotes (#1777)" in {
    redacted("x \"{'token': 't=\"d'}\"\nmsg=\"cfg={'auth_token': \"SEK\", 'a': 'b'}\"") shouldBe
      s"x \"{'token': '$R'}\"\nmsg=\"cfg={'auth_token': \"$R\", 'a': 'b'}\""
  }

  it should "not let an unclosed escaped-quote value pair with the next pair's opening quote (#1782)" in {
    // The value opened by `\"` runs to the end of its line, the next pair's value with it, whatever the header between.
    redacted("""password=\"abc def Cookie: sid=1 token=\"SEK1\" ok""") shouldBe s"""password=$R ok"""
    redacted("""password=\"abc def Kookie: sid=1 token=\"SEK1\" ok""") shouldBe s"""password=$R ok"""
  }

  it should "redact a tuple under a sensitive key, and leaves after a mention with an odd number of quotes (#1788)" in {
    redacted("""{'token': ('SEK', "SEK'")}""") shouldBe s"""{'token': ('$R', "$R")}"""
    redacted(
      ujson.Obj("msg" -> """{'note': 'see "password": " here', 'apiKey': ["SEKone", "SEKtwo"]}""").render()
    ) shouldBe
      s"""{"msg":"{'note': 'see \\"password\\": \\"$R', 'apiKey': [\\"$R\\", \\"$R\\"]}"}"""
    val depth2 = ujson
      .Obj("o" -> ujson.Obj("msg" -> """{'note': 'see "password": " here', 'apiKey': ["SEKone"]}""").render())
      .render()
    (redacted(depth2) should not).include("SEKone")
  }

  it should "redact a Bearer before a quoted pair, a query before doubly escaped JSON, and a value after \\n (#1799)" in {
    redacted("""{"h": "Bearer password=\"S3K\""}""") shouldBe s"""{"h": "Bearer password=\\"$R\\""}"""
    redacted(
      """?token=abc,{\\\"api_key\\\":\\\"S3K\\\"}"""
    ) shouldBe s"""?token=$R,{\\\\\\"api_key\\\\\\":\\\\\\"$R\\\\\\"}"""
    redacted("""{"log": "x\ntoken='S3K'"}""") shouldBe s"""{"log": "x\\ntoken='$R'"}"""
  }

  it should "keep the inner JSON whole where an empty pair value ends a nested string (#1800)" in {
    Seq(
      """{"o":"{\"a\":\"password=\",\"b\":1}"}""",
      """{"o":"{\"a\":\"Basic token=\",\"b\":1}"}""",
      """{"o":"{\"a\":\"x\\ntoken=\",\"b\":\"c\"}"}""",
      """{"o":"{\"a\":\"Bearer password=\"}","p":1}"""
    ).foreach { input =>
      redacted(input) shouldBe input
      parses(ujson.read(input)("o").str) shouldBe true
    }
  }

  it should "redact a sensitive key in indented YAML, in every scalar style and nested (#1844)" in {
    redacted(
      "spec:\n  client_assertion: eyJhbGciOiJSUzI1NiJ9xyzABC\n  saml_assertion: 'PHNhbWw6QXNzZXJ0aW9u'\n" +
        "  samlAssertion: \"PHNhbWw6QXNzZXJ0aW9u2\"\n  name: app"
    ) shouldBe s"spec:\n  client_assertion: $R\n  saml_assertion: $R\n  samlAssertion: $R\n  name: app"
    redacted(
      "database:\n  host: db\n  password: hunter2\n  nested:\n    apiKey: sk-test-123\n  - token: abc123"
    ) shouldBe
      s"database:\n  host: db\n  password: $R\n  nested:\n    apiKey: $R\n  - token: $R"
    redacted(ujson.Obj("config" -> "auth:\n  client_secret: 's3cr3t'\n  port: 1").render()) shouldBe
      s"""{"config":"auth:\\n  client_secret: $R\\n  port: 1"}"""
    // a block under a sensitive key, folded onto the indented lines after it
    redacted("credentials:\n  user: bob\n  pass: hunter2\nport: 1") shouldBe s"credentials:\n  $R\nport: 1"
  }
}
