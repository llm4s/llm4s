package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Values whose structure runs over line breaks (#1932, PR #1959 review): a call whose first argument starts on the
 * next line, keyword arguments one per line, pretty-printed JSON and Python reprs with a value on its own line - with
 * `\n`, `\r\n` and tabs. Inside a container or a call, a line break is whitespace like any other; only a prose
 * `key: value` line and a `key=value` line end at the line break.
 */
class RedactionMultilineSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** The same document with `\n` line breaks, `\r\n` ones, and tabs for its indentation. */
  private def variants(doc: String): Seq[String] =
    Seq(doc, doc.replace("\n", "\r\n"), doc.replace("  ", "\t"), doc.replace("\n", "\r\n").replace("  ", "\t"))

  private def assertMasked(doc: String, secrets: String*): Unit =
    variants(doc).foreach { d =>
      val out = Redaction.redact(d)
      secrets.foreach(s => withClue(s"input: ${d.replace("\r", "\\r")}\noutput: $out\n")((out should not).include(s)))
    }

  "Redaction.redact" should "replace a call's argument that starts on the line after its open bracket" in {
    Redaction.redact("{'token': SecretStr(\n'hunter2'\n)}") shouldBe s"{'token': SecretStr(\n'$R'\n)}"
    assertMasked("{'token': SecretStr(\n'hunter2'\n)}", "hunter2")
    assertMasked("{'token': SecretStr(\n  'hunter2'\n)}", "hunter2")
    assertMasked("token=SecretStr(\n  'hunter2'\n)", "hunter2")
    assertMasked("{\"password\": Secret(\n  value=\"hunter2\"\n)}", "hunter2")
  }

  it should "replace keyword arguments written one per line" in {
    assertMasked("Settings(\n  token='Kq81xQ',\n  user='u'\n)", "Kq81xQ")
    assertMasked("Settings(\n  token=\n    'Kq81xQ',\n  user='u'\n)", "Kq81xQ")
    assertMasked("Settings(\n  token = \n    'Kq81xQ'\n)", "Kq81xQ")
    assertMasked("{'credentials': Settings(\n  user='Kq81xQ',\n  token='Zm72yR'\n)}", "Kq81xQ", "Zm72yR")
    assertMasked("Settings(\n  api_key=SecretStr(\n    'Kq81xQ'\n  ),\n  retries=3\n)", "Kq81xQ")
    assertMasked("Settings(token= 'Kq81xQ', user='u')", "Kq81xQ")
    assertMasked("Settings(\n  token\n  =\n  'Kq81xQ'\n)", "Kq81xQ")
  }

  it should "replace the leaves of pretty-printed JSON under a sensitive key, values on lines of their own" in {
    val doc =
      "{\n  \"credentials\":\n  {\n    \"user\":\n      \"Kq81xQ\",\n    \"nested\":\n    [\n      \"Zm72yR\",\n" +
        "      {\n        \"k\":\n          \"Wp55tU\"\n      }\n    ]\n  },\n  \"model\": \"gpt-4o\"\n}"
    assertMasked(doc, "Kq81xQ", "Zm72yR", "Wp55tU")
    variants(doc).foreach { d =>
      val out = Redaction.redact(d)
      parses(out) shouldBe true
      out should include("gpt-4o")
    }
    assertMasked("{\n  \"token\"\n  :\n  \"Kq81xQ\"\n}", "Kq81xQ")
    assertMasked("{\n  \"token\":\n\n  12345678\n}", "12345678")
  }

  it should "replace the leaves of a pretty-printed Python repr under a sensitive key" in {
    assertMasked("{'secrets': [\n  'Kq81xQ',\n  'Zm72yR'\n]}", "Kq81xQ", "Zm72yR")
    assertMasked("{'secrets':\n  (\n    'Kq81xQ',\n  )\n}", "Kq81xQ")
    assertMasked("{\n  'api_key':\n    'Kq81xQ',\n  'n': 1\n}", "Kq81xQ")
  }

  it should "replace a sensitive bare key's value on the next line inside a bracket" in {
    assertMasked("{password:\n  'Kq81xQ'}", "Kq81xQ")
    assertMasked("{a: 1, token:\n  \"Kq81xQ\"}", "Kq81xQ")
  }

  it should "replace a sensitive key's value with whitespace before its `:` or `=` (PR #1959 review)" in {
    // a bare key inside a bracket: any whitespace before the `:`
    Redaction.redact("{password : 'hunter2'}") shouldBe s"{password : '$R'}"
    assertMasked("{password : 'Kq81xQ'}", "Kq81xQ")
    assertMasked("{password\n: 'Kq81xQ'}", "Kq81xQ")
    assertMasked("{a: 1,\n  token\n    :\n  Kq81xQ}", "Kq81xQ")
    // a quoted key's `=`, and a leaf's `=` in a container under a sensitive key
    assertMasked("{\"password\" = \"Kq81xQ\"}", "Kq81xQ")
    assertMasked("\"password\" = \"Kq81xQ\"", "Kq81xQ")
    assertMasked("{\"token\": [note = Kq81xQ]}", "Kq81xQ")
    assertMasked("{\"token\": [password =\n  Kq81xQ]}", "Kq81xQ")
    // a call's name before its `(`, and a header's name before its `:`
    assertMasked("Settings(token=SecretStr ('Kq81xQ'))", "Kq81xQ")
    assertMasked("token = SecretStr ('Kq81xQ')", "Kq81xQ")
    assertMasked("password : Kq81xQ\nnext: 1", "Kq81xQ")
    assertMasked("x\nAuthorization : Bearer Kq81xQ", "Kq81xQ")
    // a blank after a glued `=` before a value that looks like a credential
    assertMasked("password= Kq81xQ", "Kq81xQ")
  }

  it should "still end a prose key: value line and a key=value line at the line break" in {
    Redaction.redact("Reset your password:\nthe link expires") shouldBe "Reset your password:\nthe link expires"
    Redaction.redact("token=\nnext=1") shouldBe "token=\nnext=1"
    Redaction.redact("token=\r\nnext=1") shouldBe "token=\r\nnext=1"
    Redaction.redact("password:\r\nhello: world") shouldBe "password:\r\nhello: world"
    Redaction.redact("token= see the docs") shouldBe "token= see the docs"
    // outside a bracket, a line break between a bare key and its `:` or `=` ends the key's line
    Redaction.redact("password\n: the link expires") shouldBe "password\n: the link expires"
  }
}
