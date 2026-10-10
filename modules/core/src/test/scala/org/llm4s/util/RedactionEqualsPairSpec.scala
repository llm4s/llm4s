package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * A `key=value` pair inside a string of JSON that sits inside a string: a log line, a note or a query string that a
 * prompt or a response body carries. Its value ends at the escaped quote that closes the inner string, and the
 * backslashes of that escape are the document's, not the value's. The pass that redacts the pair used to take them
 * with the value and write the placeholder before a bare quote, which ended the outer string there: the document no
 * longer parsed, and the passes after it paired the quotes the wrong way round (#1677).
 */
class RedactionEqualsPairSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** `fields` as JSON, put `depth` times inside a string under `"c"`. */
  private def nested(depth: Int, fields: (String, String)*): String =
    (1 to depth).foldLeft(ujson.Obj.from(fields.map((k, v) => k -> ujson.Str(v))).render()) { (inner, _) =>
      ujson.Obj("c" -> inner).render()
    }

  /** The inner object of a document `nested` built, after redacting it. */
  private def innerAfterRedact(depth: Int, input: String): ujson.Value = {
    val out = Redaction.redact(input)
    withClue(s"output does not parse: $out\n")(parses(out) shouldBe true)
    (1 to depth).foldLeft(ujson.read(out))((doc, _) => ujson.read(doc("c").str))
  }

  "Redaction.redact" should "keep JSON that sits inside a string valid when it redacts a key=value in it (#1677)" in {
    val input = """{"c": "{\"note\": \"token=abc\", \"x\": \"y\"}"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"c": "{\\"note\\": \\"token=$R\\", \\"x\\": \\"y\\"}"}"""
    (out should not).include("abc")
    val inner = ujson.read(ujson.read(out)("c").str)
    inner("note").str shouldBe s"token=$R"
    inner("x").str shouldBe "y"
  }

  it should "keep the escaped quote after a key=value at the end of a longer string" in {
    val input = nested(1, "note" -> "run it with password=hunter2value", "x" -> "y")
    val inner = innerAfterRedact(1, input)
    inner("note").str shouldBe s"run it with password=$R"
    inner("x").str shouldBe "y"
  }

  it should "keep a key=value in JSON nested twice and three times in strings valid" in {
    Seq(2, 3).foreach { depth =>
      withClue(s"depth $depth: ") {
        val input = nested(depth, "note" -> "token=hunter2value", "x" -> "y")
        val inner = innerAfterRedact(depth, input)
        inner("note").str shouldBe s"token=$R"
        inner("x").str shouldBe "y"
        (Redaction.redact(input) should not).include("hunter2value")
      }
    }
  }

  it should "redact a key=value in plain JSON as it did, up to the closing quote" in {
    Redaction.redact("""{"note": "token=abc", "x": "y"}""") shouldBe s"""{"note": "token=$R", "x": "y"}"""
    Redaction.redact("""{"log": "level=info password=hunter2value"}""") shouldBe
      s"""{"log": "level=info password=$R"}"""
  }

  it should "redact the backslashes of a value, and keep only those that escape the closing quote" in {
    Seq("ab\\cd", "abc\\", "abc\\\\", "\\abc", "\\\\abc\\\\").foreach { value =>
      withClue(s"value $value: ") {
        val input = nested(1, "note" -> s"token=$value", "x" -> "y")
        val inner = innerAfterRedact(1, input)
        inner("note").str should startWith(s"token=$R")
        inner("x").str shouldBe "y"
        (inner("note").str should not).include("\\")
      }
    }
  }

  it should "end a value at a quote inside it, as in plain JSON, and keep the document valid" in {
    Seq(1, 2).foreach { depth =>
      val inner = innerAfterRedact(depth, nested(depth, "note" -> "token=hunter2\"tail", "x" -> "y"))
      inner("note").str should startWith(s"token=$R")
      (inner("note").str should not).include("hunter2")
      inner("x").str shouldBe "y"
    }
  }

  it should "keep JSON inside a string valid when a query string in it holds several pairs" in {
    Seq("a=1&token=hunter2value", "a=1&token=hunter2value&b=2", "token=hunter2value&a=1").foreach { query =>
      withClue(s"query $query: ") {
        Seq(1, 2).foreach { depth =>
          val inner = innerAfterRedact(depth, nested(depth, "note" -> query, "x" -> "y"))
          (inner("note").str should not).include("hunter2value")
          inner("note").str should include(s"token=$R")
          inner("x").str shouldBe "y"
        }
      }
    }
  }

  it should "keep a logfmt line in JSON inside a string valid" in {
    val inner = innerAfterRedact(1, nested(1, "log" -> "level=info user=ann api_key=hunter2value"))
    inner("log").str shouldBe s"level=info user=ann api_key=$R"
  }

  it should "keep the escaped closing quote of single-quoted text inside a single-quoted string" in {
    Redaction.redact("""{'c': '{\'note\': \'token=abc\', \'x\': \'y\'}'}""") shouldBe
      s"""{'c': '{\\'note\\': \\'token=$R\\', \\'x\\': \\'y\\'}'}"""
  }

  it should "leave a key=value whose value ends in backslashes before no quote as it was" in {
    Redaction.redact("token=abc\\ next") shouldBe s"token=$R next"
    Redaction.redact("token=abc\\") shouldBe s"token=$R"
  }

  // ---------------------------------------------------------------------------------------------
  // A logfmt value in quotes escaped with backslashes (#1684)
  // ---------------------------------------------------------------------------------------------

  /** `value` as logfmt writes a quoted value: in `"`, with its `\` and `"` escaped. */
  private def logfmtQuoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  "Redaction.redact" should "redact a logfmt value in quotes inside a JSON string, at depths 1 to 3 (#1684)" in {
    Seq(1, 2, 3).foreach { depth =>
      withClue(s"depth $depth: ") {
        val input = nested(depth, "message" -> """login user=bob password="hunter2" ok""")
        val inner = innerAfterRedact(depth, input)
        inner("message").str shouldBe s"""login user=bob password="$R" ok"""
        (Redaction.redact(input) should not).include("hunter2")
      }
    }
    Redaction.redact("""{"message": "login user=bob password=\"hunter2\" ok"}""") shouldBe
      s"""{"message": "login user=bob password=\\"$R\\" ok"}"""
  }

  it should "redact a value in quotes escaped with backslashes in plain text, double or single (#1684)" in {
    Redaction.redact("""login user=bob token=\"abc def\" ok""") shouldBe s"""login user=bob token=\\"$R\\" ok"""
    Redaction.redact("""login password=\'hunter2 x\' ok""") shouldBe s"""login password=\\'$R\\' ok"""
    Redaction.redact("""'login password=\'hunter2\' ok'""") shouldBe s"""'login password=\\'$R\\' ok'"""
    // A value escaped twice, in Python's repr of a string that already holds one.
    Redaction.redact("""x='a password=\\\'hunter2\\\' b'""") shouldBe s"""x='a password=\\\\\\'$R\\\\\\' b'"""
  }

  it should "run a value whose escaped quote is never closed to the end of its string, and keep the JSON valid" in {
    Redaction.redact("""{"message": "login password=\"hunter2 and more", "x": "y"}""") shouldBe
      s"""{"message": "login password=\\"$R", "x": "y"}"""
    Seq(1, 2, 3).foreach { depth =>
      withClue(s"depth $depth: ") {
        val input = nested(depth, "message" -> "login password=\"hunter2 and more", "x" -> "y")
        val inner = innerAfterRedact(depth, input)
        inner("message").str shouldBe s"login password=\"$R"
        inner("x").str shouldBe "y"
      }
    }
    // Plain text: to the end of the line, or of the input.
    Redaction.redact("token=\\\"abc def\nnext=1") shouldBe s"token=\\\"$R\nnext=1"
    Redaction.redact("token=\\\"abc def") shouldBe s"token=\\\"$R"
  }

  it should "redact every pair of a line whose values are in escaped quotes, and keep the others" in {
    Seq(1, 2).foreach { depth =>
      withClue(s"depth $depth: ") {
        val line  = """level=info token="a b" user="bob" client_secret="c, d" note="x=y" api_key=plain"""
        val inner = innerAfterRedact(depth, nested(depth, "log" -> line, "x" -> "y"))
        inner("log").str shouldBe
          s"""level=info token="$R" user="bob" client_secret="$R" note="x=y" api_key=$R"""
        inner("x").str shouldBe "y"
      }
    }
  }

  it should "read a backslash or a quote escaped inside the value as the value's, at depths 1 and 2" in {
    Seq("ab\\", "ab\"cd", "\\", "\"", "a\\\"b", "\\\\x\"\"", "it's").foreach { value =>
      Seq(1, 2).foreach { depth =>
        withClue(s"value $value, depth $depth: ") {
          val secret = s"Zq${value}Wx"
          val input  = nested(depth, "log" -> s"login password=${logfmtQuoted(secret)} ok", "x" -> "y")
          val inner  = innerAfterRedact(depth, input)
          inner("log").str shouldBe s"""login password="$R" ok"""
          inner("x").str shouldBe "y"
        }
      }
    }
  }

  it should "leave a value in escaped quotes under a key that is not sensitive, and an empty one, as they are" in {
    Seq(
      """{"message": "login user=\"bob\" ok"}""",
      """{"message": "login password=\"\" ok"}""",
      """login user=\'bob\' ok"""
    ).foreach(input => withClue(input)(Redaction.redact(input) shouldBe input))
  }

  it should "read a value after an even run of backslashes and a quote as before: the run escapes no quote" in {
    // `\\` is an escaped backslash, and the quote after it ends the string.
    val input = "{\"message\": \"login password=\\\\\", \"x\": \"hunter2\"}"
    val out   = Redaction.redact(input)
    out shouldBe s"""{"message": "login password=$R", "x": "hunter2"}"""
    parses(out) shouldBe true
  }

  it should "read values in escaped quotes in time linear in the input" in {
    val shapes: Seq[(String, Int => String)] = Seq(
      "many never closed"             -> (n => "password=\\\"a " * n),
      "many never closed in a string" -> (n => "{\"m\":\"" + "token=\\\"x " * n + "\"}"),
      "a long backslash run"          -> (n => "password=\\\"" + "\\" * (n * 10) + "x\\\""),
      "quotes escaped deeper"         -> (n => "secret=\\\"" + "a\\\\\\\"b" * n),
      "quotes escaped less deep"      -> (n => "secret=\\\\\\\"a\\\"" * n),
      "closed, many times"            -> (n => "token=\\'a b\\' " * n)
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(5000), build(20000))(Redaction.redact(_))
    }
  }

  // As in RedactionShapesSpec: a deliberately small stack, so that a pattern or a scan that recurses per character
  // fails on any machine.
  private def onSmallStack(input: String): String = {
    @volatile var result: Option[String]     = None
    @volatile var failure: Option[Throwable] = None
    val thread = new Thread(null, () => result = Some(Redaction.redact(input)), "redaction-small-stack", 256L * 1024)
    thread.setUncaughtExceptionHandler((_, e) => failure = Some(e))
    thread.start()
    thread.join(120000L)
    withClue("the redaction did not finish within two minutes: ")(thread.isAlive shouldBe false)
    withClue("redaction failed on a thread with a 256 KB stack: ")(failure shouldBe None)
    result.getOrElse(fail("redaction produced no result"))
  }

  it should "redact a key=value before a megabyte-long run of backslashes, and many '=', on a small stack" in {
    onSmallStack("token=a" + ("\\" * 1000001) + "\"") shouldBe s"token=$R\\\""
    onSmallStack("token=a" + ("\\" * 1000000) + "\"") shouldBe s"token=$R\""
    onSmallStack("token=" * 200000 + "\\\"") shouldBe s"token=$R\\\""
    onSmallStack("a=" * 500000) shouldBe "a=" * 500000
    val pairs = onSmallStack(nested(1, "note" -> ("token=hunter2value " * 50000), "x" -> "y"))
    (pairs should not).include("hunter2value")
    parses(pairs) shouldBe true
  }
}
