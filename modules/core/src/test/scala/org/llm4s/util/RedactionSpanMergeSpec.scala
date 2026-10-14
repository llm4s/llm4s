package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Text that holds the JSON escape of a separator or a quote (#1676), as Go's `encoding/json`, System.Text.Json and Gson
 * write them. The passes of `Redaction.redact` each read such text both as escaped and as it was given, and merged
 * what they replaced; since #1932 the escapes are decoded, a level at a time, and the text read once at the level they
 * stand for. A query value that holds a quote runs over an escaped `&` (a quoted credential may hold one), and so over
 * the fields after it, which are replaced with it: none can take the key of the field after it and leave its value
 * readable.
 */
class RedactionSpanMergeSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /**
   * `~26`, `~3d`, `~3f`, `~27`, `~22` and `~3c` stand for the JSON escapes of `&`, `=`, `?`, `'`, `"` and `<` (a
   * backslash, `u00` and the hex digits), as in `RedactionQueryParamSpec`: Scala reads a backslash-u sequence in a
   * string literal as the character it encodes.
   */
  private def u(s: String): String = s.replace("~", "\\" + "u00")

  /** The redacted input, which holds none of `secrets` and, where the input is JSON, still parses. */
  private def redacted(input: String, secrets: String*): String = {
    val out = Redaction.redact(u(input))
    secrets.foreach(secret => withClue(s"in $out: ")((out should not).include(secret)))
    if (parses(u(input))) withClue(s"$out: ")(parses(out) shouldBe true)
    out
  }

  // ---------------------------------------------------------------------------------------------
  // A query value that holds a quote runs over an escaped '&', and used to take the key of the field after it
  // ---------------------------------------------------------------------------------------------

  "Redaction.redact" should "redact a quoted password after a query value that holds a quote, as Go writes it" in {
    redacted(
      """{"msg":"GET https://h/x?a=1~26token=ab'cd~26password='correct horse battery'"}""",
      "ab'cd",
      "correct",
      "horse",
      "battery"
    ) shouldBe u(raw"""{"msg":"GET https://h/x?a=1~26token=$R"}""")
  }

  it should "redact a quoted client_secret after a query value that holds a quote" in {
    redacted(
      """{"msg":"GET https://h/x?a=1~26token=ab'cd~26client_secret='s3cr3t value'"}""",
      "s3cr3t",
      "value"
    ) shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26token=$R"}""")
  }

  it should "redact a quoted password after such a value in a form body" in {
    redacted(
      """{"msg":"POST /login body: user=bob~26token=ab'cd~26password='correct horse battery'"}""",
      "horse",
      "battery"
    ) shouldBe u(raw"""{"msg":"POST /login body: user=bob~26token=$R"}""")
  }

  it should "redact a single-quoted field whose opening quote such a value runs over" in {
    redacted("""{"msg":"GET https://h/x?a=1~26token='K~26'passwd':'hunter2'"}""", "hunter2") shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26token=$R"}""")
    redacted("""{"msg":"GET https://h/x?a=1~26token=it\"s~26'passwd':'hunter2'"}""", "hunter2") shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26token=$R"}""")
  }

  it should "redact a double-quoted JSON field whose key such a value runs over" in {
    redacted("""{"msg":"GET https://h/x?a=1~26token=ab'cd~26\"password\":\"hunter2\""}""", "hunter2") shouldBe
      u(raw"""{"msg":"GET https://h/x?a=1~26token=$R"}""")
  }

  it should "redact the same fields where '=' and '?' are escaped too" in {
    redacted(
      """{"msg":"GET https://h/x~3fa~3d1~26token~3dab'cd~26password~3d'correct horse battery'"}""",
      "horse",
      "battery"
    ) shouldBe u(raw"""{"msg":"GET https://h/x~3fa~3d1~26token~3d$R"}""")
  }

  it should "redact the same document as before where nothing is escaped" in {
    Redaction.redact("""{"msg":"GET https://h/x?a=1&token=ab'cd&password='correct horse battery'"}""") shouldBe
      s"""{"msg":"GET https://h/x?a=1&token=$R&password='$R'"}"""
  }

  it should "still keep the parameters after a sensitive one that holds no quote readable" in {
    redacted("""{"msg":"GET https://h/x?token=abc~26page=2~26user=bob"}""", "abc") shouldBe
      u(raw"""{"msg":"GET https://h/x?token=$R~26page=2~26user=bob"}""")
  }

  // ---------------------------------------------------------------------------------------------
  // Bearer and Basic tokens after an escape: a match there no longer takes the keyword of the next one
  // ---------------------------------------------------------------------------------------------

  it should "redact a Basic token after an escape without taking the key or the keyword after it" in {
    // #1932: `Basic token=...` is the pair `token=` after a scheme word, whose value is replaced; `Basic` before a
    // token, the escape of `=` and all, is replaced with it
    redacted("~3fBasic token=~3d6G3J", "6G3J") shouldBe u(s"~3fBasic token=$R")
    redacted("Basic ~3DBasic Basic 9K29", "9K29") shouldBe u(s"$R $R")
  }

  // ---------------------------------------------------------------------------------------------
  // JSON whose quotes are escapes, as System.Text.Json writes a document inside a string
  // ---------------------------------------------------------------------------------------------

  it should "redact a field of JSON whose quotes System.Text.Json escapes, after a query that holds an escaped '&'" in {
    redacted(
      """{"msg":"{~22url~22:~22https://h/x?token=abc~26page=2~22,~22nested~22:{~22token~22:~22NESTEDSECRET~22}}"}""",
      "abc",
      "NESTEDSECRET"
    ) shouldBe
      u(raw"""{"msg":"{~22url~22:~22https://h/x?token=$R~26page=2~22,~22nested~22:{~22token~22:~22$R~22}}"}""")
  }

  it should "redact a single-quoted dict field whose quotes are escaped" in {
    redacted("""{"msg":"{~27password~27: ~27hunter two~27, ~27user~27: ~27bob~27}"}""", "hunter") shouldBe
      u(raw"""{"msg":"{~27password~27: ~27$R~27, ~27user~27: ~27bob~27}"}""")
  }

  // ---------------------------------------------------------------------------------------------
  // key=value pairs: a value that is kept ends at an escaped quote, '<' or '>', as it ends at the character
  // ---------------------------------------------------------------------------------------------

  it should "redact a key=value credential in escaped quotes after a pair that is kept" in {
    redacted("""{"msg":"code=wxz\nAPI_KEY=~27XQ1L1RV8~22WX879~27\nn=1"}""", "XQ1L1RV8", "WX879") shouldBe
      u(raw"""{"msg":"code=wxz\nAPI_KEY=~27$R~27\nn=1"}""")
  }

  it should "read the pair after a kept value that holds an escaped '<' or an escaped quote" in {
    // In a document that holds the escape of a quote or a separator, as System.Text.Json writes one
    redacted("""{"msg":"x=YX5~3cR7K\nDB_PASSWORD=57WL6G","note":"it~27s"}""", "57WL6G") shouldBe
      u(raw"""{"msg":"x=YX5~3cR7K\nDB_PASSWORD=$R","note":"it~27s"}""")
    redacted("""{"msg":"note=~22a~22}\nDB_PASSWORD=5MY4RZN4~26x=1"}""", "5MY4RZN4") shouldBe
      u(raw"""{"msg":"note=~22a~22}\nDB_PASSWORD=$R~26x=1"}""")
  }

  it should "redact a key=value credential in escaped quotes that a sensitive value before it runs into" in {
    redacted("""{"msg":"DB_PASSWORD=95MV9GZ\nAPI_KEY=~27757Z7J2~26x=2ZRWX!8G754~27\nn=1"}""", "2ZRWX", "8G754")
  }

  // ---------------------------------------------------------------------------------------------
  // A key starts where it starts in the text the escapes stand for, never at the 'u' of an escape
  // ---------------------------------------------------------------------------------------------

  it should "not read the escape of a quote before an escaped '=' as a key that holds the pair after it" in {
    // `u0027` and the escape of `=` read as a pair whose value was `password=...`, and the credential was never read
    redacted("~27~3dpassword=hunter2xyz", "hunter2xyz") shouldBe u(s"~27~3dpassword=$R")
    redacted("~27~3dfoo/token=hunter2xyz", "hunter2xyz") shouldBe u(s"~27~3dfoo/token=$R")
    redacted("""{"m":"x~27~3dfoo:password=hunter2xyz"}""", "hunter2xyz") shouldBe
      u(raw"""{"m":"x~27~3dfoo:password=$R"}""")
    redacted("""{"m":"cmp ~3c~3dpassword=hunter2xyz rest"}""", "hunter2xyz") shouldBe
      u(raw"""{"m":"cmp ~3c~3dpassword=$R rest"}""")
  }

  it should "not read the escape of any character that ends a word as a key" in {
    Seq(
      "~22~3dclient_secret=S7K2Q9XW" -> "~22~3dclient_secret=",
      "~3c~3dpassword=S7K2Q9XW"      -> "~3c~3dpassword=",
      "~3e~3dapi_key=S7K2Q9XW"       -> "~3e~3dapi_key=",
      "~2b~3dpassword=S7K2Q9XW"      -> "~2b~3dpassword=",
      "~60~3dapi_key=S7K2Q9XW"       -> "~60~3dapi_key=",
      "~e9~3dpassword=S7K2Q9XW"      -> "~e9~3dpassword="
    ).foreach { case (input, kept) => redacted(input, "S7K2Q9XW") shouldBe u(kept) + R }
  }

  it should "not read an escape as a key when the escape is escaped again, inside JSON in a string" in {
    redacted("""\~27\~3dsecret=S7K2Q9XW""", "S7K2Q9XW") shouldBe u(s"""\\~27\\~3dsecret=$R""")
    redacted("""\~27=secret=S7K2Q9XW""", "S7K2Q9XW") shouldBe u(s"""\\~27=secret=$R""")
  }

  it should "start a key right after the escape of a character that ends a word, as after the character" in {
    redacted("~e9password=S7K2Q9XW~26a=1", "S7K2Q9XW") shouldBe u(s"~e9password=$R~26a=1")
    redacted("~27password=S7K2Q9XW", "S7K2Q9XW") shouldBe u(s"~27password=$R")
  }

  // ---------------------------------------------------------------------------------------------
  // A key that is not sensitive, before the escape of '=', does not take the pair after the escape for its value
  // ---------------------------------------------------------------------------------------------

  it should "read the pair after the escape of '=' that ends a key starting at the escape of a key character" in {
    // #1932: the escapes are decoded, so `~70assword~3d` is `password=` itself, a sensitive key, whose value - the
    // pair it seemed to hold, `password=...`, with it - is replaced
    Seq(
      "~70assword~3dpassword=Z610x1K9mQ" -> "~70assword~3d",
      "~50ASSWORD~3dpassword=Z671x5K9mQ" -> "~50ASSWORD~3d",
      "~61pi_key~3dpassword=Z560x4K9mQ"  -> "~61pi_key~3d",
      "api~5fkey~3dpassword=Z560x4K9mQ"  -> "api~5fkey~3d",
      "~31~3dpassword=Z306x2K9mQ"        -> "~31~3dpassword=",
      "~5f~3dpassword=Z429x2K9mQ"        -> "~5f~3dpassword=",
      "~5F~3dpassword=Z429x2K9mQ"        -> "~5F~3dpassword=",
      "~2d~3dAPI_KEY=Z523x1K9mQ"         -> "~2d~3dAPI_KEY="
    ).foreach { case (input, kept) =>
      redacted(input, "Z610x1", "Z671x5", "Z560x4", "Z306x2", "Z429x2", "Z523x1") shouldBe u(kept) + R
    }
  }

  it should "read the pair after the escape of '=' when the escapes are escaped again, inside JSON in a string" in {
    redacted("""\~70assword\~3dpassword=S7K2Q9XW""", "S7K2Q9XW") shouldBe
      u(s"""\\~70assword\\~3d$R""")
  }

  it should "read the pair after the escape of '=' that ends any key that is not sensitive" in {
    redacted("abc~3dpassword=S7K2Q9XW", "S7K2Q9XW") shouldBe u(s"abc~3dpassword=$R")
    redacted("x~3d~70assword~3dpassword=S7K2Q9XW", "S7K2Q9XW") shouldBe u(s"x~3d~70assword~3d$R")
    redacted("""abc~3d"x password="S7K2Q9XW"""", "S7K2Q9XW") shouldBe u(s"""abc~3d"x password="$R"""")
    redacted("abc~3d'x password='S7K2Q9XW'", "S7K2Q9XW") shouldBe u(s"abc~3d'x password='$R'")
    redacted("""~70assword~3d"x password="S7K2Q9XW"""", "S7K2Q9XW") shouldBe u(s"""~70assword~3d$R""")
    redacted("?a=1~26~70assword~3dtoken=S7K2Q9XW", "S7K2Q9XW") shouldBe u(s"?a=1~26~70assword~3d$R")
  }

  it should "still read a key that starts at the escape of a letter, before a bare '='" in {
    redacted("~41api_key=S7K2Q9XW", "S7K2Q9XW") shouldBe u(s"~41api_key=$R")
  }

  // ---------------------------------------------------------------------------------------------
  // The escape of whitespace ends a query read through its escapes, as whitespace does
  // ---------------------------------------------------------------------------------------------

  it should "not read a query key across the escape of whitespace, into the field after it" in {
    Seq("0a", "0A", "0d", "09", "20").foreach { space =>
      redacted(
        s"""~3f~${space}token~3dZ431x3K9mQ"password":Basic Basic Z431x5K9mQ""",
        "Z431x3K9mQ",
        "Z431x5K9mQ"
      )
    }
  }

  it should "end a query value read through its escapes at the escape of whitespace" in {
    redacted("""~3ftoken~3d~20\"password\":Basic Z818x2K9mQ\\\\~27Z818x6K9mQ""", "Z818x2K9mQ", "Z818x6K9mQ")
    redacted("""~3f~20token~3dZ431x3K9mQ"password":Basic Basic Z431x5K9mQ""", "Z431x3K9mQ", "Z431x5K9mQ")
  }

  it should "run a Basic token over the escape of '=', as over '='" in {
    redacted("Basic abc~3dS7K2Q9XW", "S7K2Q9XW") shouldBe R
    redacted("""password~3d\\"password\":Basic Z642x2K9mQtoken~3dZ642x3K9mQ""", "Z642x2K9mQ", "Z642x3K9mQ")
  }

  it should "read a long Basic token that holds escapes of '=' in a loop, on a small stack" in {
    val input  = "Basic " + u("a~3d") * 50000
    var result = ""
    val thread = new Thread(null, () => result = Redaction.redact(input), "small-stack", 256 * 1024)
    thread.start()
    thread.join()
    result shouldBe R
  }

  // ---------------------------------------------------------------------------------------------
  // What the passes in sequence redact is kept: a placeholder one pass writes can let the pass after it read on
  // ---------------------------------------------------------------------------------------------

  it should "redact what the passes in sequence redact, as for an input with no escape" in {
    redacted("""{"msg":"https://h/cb?credential='R2736'L2K7 76XZ81Q'~26id=1"}""", "R2736", "L2K7", "76XZ81Q") shouldBe
      u(raw"""{"msg":"https://h/cb?credential='$R'~26id=1"}""")
  }

  // The timing backstop is RedactionScalingSpec (`sbt testPerformance`); this one stays in `sbt test` and fails
  // on a catastrophic regression in one run of a megabyte, under a generous hang guard.
  it should "merge the passes over a megabyte well inside the hang guard" in {
    val input = u("a=1~26token=~27k~26'passwd':'v'~26 ") * 25000
    input.length should be > 1000000
    LinearTime.timed("merging the passes over a megabyte", LinearTime.MegabyteHangGuard)(Redaction.redact(input))
  }

}
