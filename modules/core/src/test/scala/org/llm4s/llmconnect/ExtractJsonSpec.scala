package org.llm4s.llmconnect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * Table-driven, adversarial and randomized tests for [[LLMClient.extractJson]].
 *
 * The contract under test: given model output that contains one JSON value (possibly wrapped in a
 * markdown fence and/or prose), return exactly that JSON text so that `ujson.read` succeeds on it.
 */
class ExtractJsonSpec extends AnyFlatSpec with Matchers {

  private def extract(s: String): String = LLMClient.extractJson(s)

  private lazy val Obj = """{"a":1}"""

  // (description, input, expected output of extractJson)
  private lazy val cases: Seq[(String, String, String)] = Seq(
    ("plain object", Obj, Obj),
    ("plain object with surrounding whitespace", s"  \n$Obj\t\n", Obj),
    ("plain array", "[1,2,3]", "[1,2,3]"),
    ("nested objects", """{"a":{"b":{"c":[1,{"d":2}]}}}""", """{"a":{"b":{"c":[1,{"d":2}]}}}"""),
    ("json fence", s"```json\n$Obj\n```", Obj),
    ("bare fence", s"```\n$Obj\n```", Obj),
    ("fence with uppercase tag", s"```JSON\n$Obj\n```", Obj),
    ("fence with CRLF", s"```json\r\n$Obj\r\n```", Obj),
    ("fence with trailing spaces after tag", s"```json   \n$Obj\n```", Obj),
    ("fence with trailing whitespace after close", s"```json\n$Obj\n```  \n\n", Obj),
    ("fence without newline before close", s"```json\n$Obj```", Obj),
    ("fence all on one line", s"```$Obj```", Obj),
    ("fence without closing fence", s"```json\n$Obj", Obj),
    ("fence without closing fence but trailing prose", s"```json\n$Obj\nThat is all", Obj),
    (
      "pretty printed in fence",
      "```json\n{\n  \"a\": [\n    1,\n    2\n  ]\n}\n```",
      "{\n  \"a\": [\n    1,\n    2\n  ]\n}"
    ),
    ("prose before object", s"Here you go: $Obj", Obj),
    ("prose before array", "Result: [1,2]", "[1,2]"),
    ("prose before fence", s"Here:\n```json\n$Obj\n```", Obj),
    ("prose before and after fence", s"Here:\n```json\n$Obj\n```\nEnjoy!", Obj),
    ("prose after object", s"$Obj Hope that helps!", Obj),
    ("prose after array", "[1,2] hope that helps", "[1,2]"),
    ("two objects: first is chosen", """{"a":1} and {"b":2}""", Obj),
    ("two objects on separate lines starting the text", "{\"a\":1}\n{\"b\":2}", Obj),
    ("closing brace inside string", """x {"a":"}"} y""", """{"a":"}"}"""),
    ("opening brace inside string", """x {"a":"{{{"} y""", """{"a":"{{{"}"""),
    ("bracket inside string", """x {"a":"]["} y""", """{"a":"]["}"""),
    ("escaped quote inside string", """x {"a":"say \"hi\" }"} y""", """{"a":"say \"hi\" }"}"""),
    ("escaped quote directly followed by closing brace in string", """x {"a":"\"}"} y""", """{"a":"\"}"}"""),
    ("escaped quote then bracket in string", """x ["\"]", 1] y""", """["\"]", 1]"""),
    ("string ending in escaped backslash", """x {"a":"c:\\"} y""", """{"a":"c:\\"}"""),
    ("double escaped backslash before quote", """x {"a":"\\\\"} y""", """{"a":"\\\\"}"""),
    ("unicode escape", """x {"a":"\u007d\u0022"} y""", """{"a":"\u007d\u0022"}"""),
    (
      "literal non-ascii text",
      "pre {\"a\":\"caf\u00e9 \u4e2d\u6587 \ud83d\ude00\"} post",
      "{\"a\":\"caf\u00e9 \u4e2d\u6587 \ud83d\ude00\"}"
    ),
    ("array of objects after prose", """List: [{"a":1},{"a":2}] done""", """[{"a":1},{"a":2}]"""),
    ("stray opening brace in prose before the real json", """I'll use a { here. {"a":1}""", Obj),
    ("non-json braces in prose before the real json", """Format is {key: value}. Answer: {"a":1}""", Obj),
    ("template placeholder before real json", """Replace {name} with: {"name":"x"}""", """{"name":"x"}"""),
    ("empty object", "{}", "{}"),
    ("empty array", "[]", "[]"),
    ("top-level number", "42", "42"),
    ("top-level string", "\"hello\"", "\"hello\""),
    ("top-level string containing braces", "\"a{b}c\"", "\"a{b}c\""),
    ("top-level boolean", "true", "true"),
    ("top-level null", "null", "null")
  )

  "LLMClient.extractJson" should "recover the expected JSON text for every table-driven case" in {
    val failures = cases.flatMap { case (name, input, expected) =>
      val actual = extract(input)
      if (actual == expected) None else Some(s"[$name] input=<$input> expected=<$expected> actual=<$actual>")
    }
    withClue(failures.mkString("\n", "\n", "\n"))(failures shouldBe empty)
  }

  it should "give a parseable result for every table-driven case" in {
    val failures = cases.flatMap { case (name, input, _) =>
      val actual = extract(input)
      if (Try(ujson.read(actual)).isSuccess) None else Some(s"[$name] <$actual>")
    }
    withClue(failures.mkString("\n", "\n", "\n"))(failures shouldBe empty)
  }

  // inputs for which no JSON can be recovered: must be returned trimmed and unchanged (no exceptions)
  private lazy val unrecoverable: Seq[(String, String)] = Seq(
    ("empty", ""),
    ("whitespace only", "  \n\t "),
    ("plain prose", "I cannot help with that."),
    ("unbalanced object", """Here: {"a":1"""),
    ("unbalanced array", "[1,2"),
    ("unterminated string containing closer", """{"a":"}"""),
    ("only an opening brace", "{"),
    ("only a closing brace", "}"),
    ("mismatched closer", "{]")
  )

  it should "never throw and return the trimmed input when no JSON block exists" in {
    unrecoverable.foreach { case (name, input) =>
      withClue(s"[$name] ") {
        val out = Try(extract(input))
        out.isSuccess shouldBe true
        out.get shouldBe input.trim
      }
    }
  }

  it should "never throw on degenerate fences" in {
    Seq("```", "```json\n```", "``` ```", "````", "```json").foreach { input =>
      Try(extract(input)).isSuccess shouldBe true
    }
  }

  it should "be idempotent on its own output" in {
    cases.foreach { case (name, input, _) =>
      withClue(s"[$name] ")(extract(extract(input)) shouldBe extract(input))
    }
  }

  // ---- randomized round trip ----

  private lazy val specials =
    Vector("{", "}", "[", "]", "\"", "\\", "```", "\n", "\u00e9", "\ud83d\ude00", "\\\"", ",", ":")

  private def genString(rnd: Random): String =
    Seq
      .fill(rnd.nextInt(6))(
        if (rnd.nextBoolean()) specials(rnd.nextInt(specials.size)) else rnd.alphanumeric.take(2).mkString
      )
      .mkString

  private def genJson(rnd: Random, depth: Int): ujson.Value =
    rnd.nextInt(if (depth <= 0) 4 else 6) match {
      case 0 => ujson.Str(genString(rnd))
      case 1 => ujson.Num(rnd.nextInt(1000).toDouble)
      case 2 => ujson.Bool(rnd.nextBoolean())
      case 3 => ujson.Null
      case 4 => ujson.Arr(Seq.fill(rnd.nextInt(4))(genJson(rnd, depth - 1)): _*)
      case _ =>
        ujson.Obj.from(Seq.fill(rnd.nextInt(4))(s"k${rnd.nextInt(100)}${genString(rnd)}" -> genJson(rnd, depth - 1)))
    }

  // prose that cannot itself be mistaken for JSON (no brackets or braces)
  private lazy val proses =
    Vector("", "Sure! ", "Here is the result:\n", "Of course.\n\n", "Answer -> ", "I think so. ")
  private lazy val tails = Vector("", "\nHope this helps!", " Let me know.", "\n\nThanks")

  it should "round-trip 500 random JSON containers wrapped in fences and prose" in {
    val rnd = new Random(932L)
    (1 to 500).foreach { i =>
      val value = genJson(rnd, 3) match {
        case v @ (_: ujson.Obj | _: ujson.Arr) => v
        case v                                 => ujson.Arr(v)
      }
      val text = if (rnd.nextBoolean()) ujson.write(value) else ujson.write(value, indent = 2)
      val pre  = proses(rnd.nextInt(proses.size))
      val post = tails(rnd.nextInt(tails.size))
      val wrapped = rnd.nextInt(4) match {
        case 0 => text
        case 1 => s"$pre```json\n$text\n```$post"
        case 2 => s"$pre$text$post"
        case _ => s"$pre```\r\n$text\r\n```"
      }
      val out = extract(wrapped)
      withClue(s"iteration $i wrapped=<$wrapped> out=<$out> ") {
        Try(ujson.read(out)).toOption shouldBe Some(ujson.read(text))
      }
    }
  }

  // ---- performance guards ----

  private def timedMillis[A](f: => A): (A, Long) = {
    val t0 = System.nanoTime()
    val a  = f
    (a, (System.nanoTime() - t0) / 1000000L)
  }

  it should "handle a multi-megabyte valid document quickly" in {
    val big = ujson.write(
      ujson.Obj("items" -> ujson.Arr(Seq.fill(100000)(ujson.Obj("id" -> 1, "s" -> "x{y}z\"q")): _*))
    )
    val (out, ms) = timedMillis(extract(s"Result:\n```json\n$big\n```\nDone."))
    out shouldBe big
    ms should be < 5000L
  }

  it should "not blow up quadratically on many unbalanced openers" in {
    val input     = "{".repeat(200000)
    val (out, ms) = timedMillis(extract(input))
    out shouldBe input
    ms should be < 5000L
  }

  it should "not blow up quadratically on many non-JSON brace pairs followed by JSON" in {
    val input     = "{x} ".repeat(50000) + Obj
    val (out, ms) = timedMillis(extract(input))
    out should not be empty
    ms should be < 5000L
  }

  it should "not blow up on many fence markers and trailing whitespace" in {
    val input   = "```json\n" + "``` ".repeat(50000) + " ".repeat(100000) + "x"
    val (_, ms) = timedMillis(extract(input))
    ms should be < 5000L
  }
}
