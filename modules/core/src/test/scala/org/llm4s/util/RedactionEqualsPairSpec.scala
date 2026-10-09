package org.llm4s.util

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
