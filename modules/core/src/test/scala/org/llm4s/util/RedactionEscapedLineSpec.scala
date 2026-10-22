package org.llm4s.util

import org.llm4s.testutil.{ LinearTime, PerformanceTest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * A sensitive key at the start of a line of TOML, YAML or dotenv text held in a JSON string (#1765, #1799 item 3):
 * the line breaks are escaped (`\n`, `\r`, `\t`, `\u000a`, `\u000d`), so the key follows the letter or the digit the
 * escape ends with, and `token = "S"`, `password: S` and `token='S'` kept their values in the clear, at depth 1 and in
 * JSON inside a JSON string (depth 2).
 */
class RedactionEscapedLineSpec extends AnyFlatSpec with Matchers {

  private val R      = Redaction.RedactionPlaceholder
  private val Secret = "s3cr3tvalue"
  private val B      = "\\"

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** The text `body` as the value of a JSON field, at depth 1, or of a field of JSON inside a JSON string (depth 2). */
  private def wrapped(body: String, depth: Int): String =
    if (depth == 1) s"""{"config":"$body"}""" else s"""{"outer":"{\\"config\\":\\"$body\\"}"}"""

  /** The escape of a quote inside a string at `depth`. */
  private def quote(depth: Int): String = B * ((1 << depth) - 1) + "\""

  private val breaks = Seq("n", "r", "t", "u000a", "u000d")

  /** Each shape: a name and the body built from the escaped line break and the escaped quote of its depth. */
  private val shapes: Seq[(String, (String, String) => String)] = Seq(
    "TOML, spaces around ="       -> ((br, _) => s"[server]${br}token = $Secret${br}port = 8080"),
    "TOML, quoted"                -> ((br, q) => s"[server]${br}token = ${q}$Secret${q}${br}port = 8080"),
    "dotenv"                      -> ((br, _) => s"A=1${br}API_KEY=$Secret${br}port=8080"),
    "dotenv, quoted"              -> ((br, q) => s"A=1${br}API_KEY=${q}$Secret${q}${br}port=8080"),
    "dotenv, single-quoted, last" -> ((br, _) => s"port=8080${br}token='$Secret'"),
    "YAML, top level"             -> ((br, _) => s"server:${br}password: $Secret${br}port: 8080"),
    "YAML, indented"              -> ((br, _) => s"server:${br}  password: $Secret${br}  port: 8080"),
    "YAML, double-quoted"         -> ((br, q) => s"server:${br}  password: ${q}$Secret${q}${br}  port: 8080"),
    "YAML, single-quoted"         -> ((br, _) => s"server:${br}  password: '$Secret'${br}  port: 8080"),
    "YAML, list item"             -> ((br, _) => s"users:${br}  - name: alice${br}  - api_key: $Secret${br}port: 8080"),
    "YAML, last line"             -> ((br, _) => s"port: 8080${br}client_secret: $Secret")
  )

  private val cases: Seq[(String, String)] =
    for {
      depth         <- Seq(1, 2)
      br            <- breaks
      (name, build) <- shapes
    } yield (s"$name, \\$br, depth $depth", wrapped(build(B * (1 << (depth - 1)) + br, quote(depth)), depth))

  "Redaction.redact" should "redact a sensitive key after an escaped line break, in every shape, at depth 1 and 2" in {
    cases.foreach { case (name, in) =>
      val out = Redaction.redact(in)
      withClue(s"$name: [$in] -> [$out]: ") {
        (out should not).include(Secret)
        out should include(R)
        out should include("8080")
        parses(out) shouldBe true
      }
    }
  }

  it should "redact the example of #1765" in {
    Redaction.redact("""{"config":"[server]\nTOKEN=s3cr3tvalue\nport=8080"}""") shouldBe
      s"""{"config":"[server]\\nTOKEN=$R\\nport=8080"}"""
    Redaction.redact("""{"config":"[server]\ntoken = \"s3cr3tvalue\"\nport=8080"}""") shouldBe
      s"""{"config":"[server]\\ntoken = $R\\nport=8080"}"""
  }

  it should "redact a single-quoted value after an escaped line break (#1799 item 3)" in {
    Redaction.redact("""{"log": "x\ntoken='S3cretValue'"}""") shouldBe s"""{"log": "x\\ntoken=$R"}"""
    Redaction.redact("""{"o":"{\"log\": \"x\\ntoken='S3cretValue'\"}"}""") shouldBe
      s"""{"o":"{\\"log\\": \\"x\\\\ntoken=$R\\"}"}"""
  }

  it should "redact a YAML block in a JSON string and keep its other keys" in {
    val in =
      """{"yaml":"llm4s:\n  providers:\n    openai:\n      apiKey: sk-not-a-real-key-123\n      model: gpt-4o"}"""
    val out = Redaction.redact(in)
    (out should not).include("sk-not-a-real-key-123")
    out should include("""\n      model: gpt-4o""")
    out should include("""\n    openai:""")
    parses(out) shouldBe true
  }

  it should "redact key=\\\"value\\\" in text outside a JSON string" in {
    Seq(
      s"""token=\\"$Secret\\" next""",
      s"""log: token=\\"$Secret\\", user=bob""",
      s"""api_key=\\"$Secret\\"""",
      s"""password=\\\\\\"$Secret\\\\\\" x"""
    ).foreach { in =>
      val out = Redaction.redact(in)
      withClue(s"[$in] -> [$out]: ") {
        (out should not).include(Secret)
        out should include(R)
      }
    }
    Redaction.redact(s"""log: token=\\"$Secret\\", user=bob""") shouldBe s"""log: token=\\"$R\\", user=bob"""
  }

  it should "leave a line whose key is not sensitive as it was" in {
    Seq(
      """{"config":"[server]\nport = 8080\nhost: example.com\nuser=bob"}""",
      """{"config":"server:\n  - name: alice\n  timeout: 30"}""",
      """{"o":"{\"config\":\"a\\nport: 8080\\nmodel = gpt-4o\"}"}"""
    ).foreach(in => Redaction.redact(in) shouldBe in)
  }

  it should "not read a key that is not at the start of a line" in {
    Seq(
      """{"msg":"line one\nPlease reset your password: see the docs"}""",
      """{"msg":"note\nthe token = the one from yesterday"}""",
      """{"msg":"a\nuser token: rotated"}""",
      """{"path":"C:\\temp\\token: x"}"""
    ).foreach(in => Redaction.redact(in) shouldBe in)
  }

  it should "leave a sensitive key with no value on its line as it was" in {
    val in = """{"yaml":"secrets:\n  password:\n  port: 8080"}"""
    Redaction.redact(in) shouldBe in
  }

  it should "leave a bare assertion that reads as prose, as the other passes do (#1818)" in {
    val in = """{"log":"step 1\nassertion: status == 200"}"""
    Redaction.redact(in) shouldBe in
  }

  it should "leave its own output as it is" in {
    cases.foreach { case (name, in) =>
      val once = Redaction.redact(in)
      withClue(s"$name: [$in] -> [$once]: ")(Redaction.redact(once) shouldBe once)
    }
  }

  it should "replace all it replaced before, and more" in {
    cases.foreach { case (name, in) =>
      val (_, ranges) = Redaction.redactedWithRanges(in, singleQuotedFromInput = true)
      val (_, kept)   = Redaction.redactedWithRanges(in, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"$name: [$in]: ")(kept.filterNot(ranges.contains) shouldBe empty)
    }
  }

  it should "read escaped lines in time linear in the input" taggedAs PerformanceTest in {
    val shapes: Seq[(String, Int => String)] = Seq(
      "YAML lines"          -> (n => "{\"y\":\"" + "\\n  password: abc\\n  port: 1" * n + "\"}"),
      "TOML lines, depth 2" -> (n => "{\"o\":\"{\\\"c\\\":\\\"" + "\\\\ntoken = \\\\\\\"x\\\\\\\"" * n + "\\\"}\"}"),
      "one value of many escapes"  -> (n => "\\npassword: " + "\\\\\\\"" * n),
      "keys with no value"         -> (n => "\\npassword:" * n),
      "breaks before long keys"    -> (n => ("\\n" + "a" * 70) * n),
      "a long run of backslashes"  -> (n => "\\npassword: x" + "\\" * (n * 4)),
      "not-sensitive keys, spaced" -> (n => "\\nport =    " * n)
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(2500), build(10000))(Redaction.redact(_))
    }
  }

  // The scaling test above is PerformanceTest, run by `sbt testPerformance`; this one stays in `sbt test`.
  it should "read a megabyte of escaped YAML lines well inside the hang guard" in {
    val input = "{\"y\":\"" + ("\\n  password: abc\\n  port: 1" * 40000) + "\"}"
    input.length should be > 1000000
    LinearTime.timed("a megabyte of escaped YAML lines", LinearTime.MegabyteHangGuard)(Redaction.redact(input))
  }
}
