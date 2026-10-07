package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.{ SafeParameterExtractor, ToolCallError, ToolFunction }
import org.llm4s.toolapi.builtin.core.{ JSONResult, JSONTool }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * Dedicated tests for [[JSONTool]]: every operation it advertises (`parse`, `format`, `query`, `validate`) has a
 * success and a failure case, plus the parameter checks, the path syntax and nested, empty and top-level inputs.
 *
 * Expected values are written out as literals; none is computed from the code under test. The three
 * `pendingUntilFixed` tests record behaviour the tool does not have yet (see their comments): each starts failing,
 * and so must be promoted to a normal test, the day the tool is fixed.
 */
class JSONToolSpec extends AnyFlatSpec with Matchers {

  private val tool: ToolFunction[Map[String, Any], JSONResult] =
    JSONTool.toolSafe.fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  private def args(operation: String, json: String, path: Option[String] = None): ujson.Obj = {
    val obj = ujson.Obj("operation" -> operation, "json" -> json)
    path.foreach(p => obj("path") = p)
    obj
  }

  private def run(arguments: ujson.Value): Either[String, JSONResult] =
    tool.handler(SafeParameterExtractor(arguments))

  private def run(operation: String, json: String, path: Option[String] = None): Either[String, JSONResult] =
    run(args(operation, json, path))

  private def ok(result: Either[String, JSONResult]): JSONResult =
    result.fold(err => fail(s"Expected Right but got Left: $err"), identity)

  private def failure(result: Either[String, JSONResult]): String =
    result.fold(identity, r => fail(s"Expected Left but got Right: $r"))

  // ---- the advertised operations

  "JSONTool" should "advertise exactly the four operations it supports" in {
    val operations = tool
      .toOpenAITool(strict = false)("function")("parameters")("properties")("operation")("enum")
      .arr
      .map(_.str)
      .toList
    operations shouldBe List("parse", "format", "query", "validate")
  }

  it should "handle every operation its schema advertises (none is reported as unknown)" in {
    val advertised = tool
      .toOpenAITool(strict = false)("function")("parameters")("properties")("operation")("enum")
      .arr
      .map(_.str)
      .toList
    advertised.foreach { operation =>
      val outcome = run(operation, "{}", Some(""))
      outcome.left.toOption.foreach(err => (err should not).include("Unknown operation"))
      outcome.isRight shouldBe true
    }
  }

  // ---- parse

  it should "parse an object, an array and every top-level scalar" in {
    val expected = Seq(
      "{}"      -> ujson.Obj(),
      "[]"      -> ujson.Arr(),
      "42"      -> ujson.Num(42),
      "\"s\""   -> ujson.Str("s"),
      "null"    -> ujson.Null,
      "true"    -> ujson.True,
      "false"   -> ujson.False,
      "-1.5e1"  -> ujson.Num(-15),
      " [1] \n" -> ujson.Arr(1)
    )
    expected.foreach { case (input, value) =>
      val parsed = ok(run("parse", input))
      withClue(s"input $input: ") {
        parsed.success shouldBe true
        parsed.result shouldBe value
      }
    }
  }

  it should "parse a nested structure, reaching every level" in {
    val parsed = ok(run("parse", """{"a":{"b":[1,{"c":null}]},"e":{},"f":[]}""")).result
    parsed("a")("b")(0) shouldBe ujson.Num(1)
    parsed("a")("b")(1)("c") shouldBe ujson.Null
    parsed("e") shouldBe ujson.Obj()
    parsed("f") shouldBe ujson.Arr()
  }

  it should "keep the order of an object's keys when parsing" in {
    ok(run("parse", """{"b":1,"a":2,"c":3}""")).result.obj.keys.toList shouldBe List("b", "a", "c")
  }

  it should "decode escaped and non-ASCII characters" in {
    ok(run("parse", "\"\\u00e9\\n\\\"q\\\"\"")).result shouldBe ujson.Str("é\n\"q\"")
    ok(run("parse", "\"日本語 😀\"")).result shouldBe ujson.Str("日本語 😀")
  }

  it should "return the parsed document and its pretty-printed text together" in {
    val parsed = ok(run("parse", """{"k":[1]}"""))
    parsed.result shouldBe ujson.Obj("k" -> ujson.Arr(1))
    parsed.formatted shouldBe "{\n  \"k\": [\n    1\n  ]\n}"
  }

  it should "return an error result, not an exception, for malformed JSON" in {
    val malformed = Seq(
      ""                  -> "empty input",
      "   "               -> "only whitespace",
      "{"                 -> "unclosed object",
      "[1,"               -> "unclosed array",
      """{"a":1,}"""      -> "trailing comma",
      "{} x"              -> "trailing characters",
      "NaN"               -> "NaN",
      "{/*c*/\"a\":1}"    -> "a comment",
      "nul"               -> "a truncated literal",
      "\"unterminated"    -> "an unterminated string",
      """{"invalid": }""" -> "a missing value"
    )
    malformed.foreach { case (input, why) =>
      val outcome = Try(run("parse", input))
      withClue(s"$why ($input): ") {
        outcome.isSuccess shouldBe true
        failure(outcome.get) should startWith("Invalid JSON")
      }
    }
  }

  // ---- format

  it should "pretty-print with two-space indentation and newline-only line breaks" in {
    val formatted = ok(run("format", """{"a":{"b":[1,{"c":null}]},"e":{},"f":[]}""")).formatted
    formatted shouldBe
      """{
        |  "a": {
        |    "b": [
        |      1,
        |      {
        |        "c": null
        |      }
        |    ]
        |  },
        |  "e": {},
        |  "f": []
        |}""".stripMargin
    (formatted should not).include("\r")
  }

  it should "format empty containers and scalars compactly" in {
    Seq("{}" -> "{}", "[]" -> "[]", "7" -> "7", "\"s\"" -> "\"s\"", "null" -> "null").foreach {
      case (input, expected) =>
        ok(run("format", input)).formatted shouldBe expected
    }
  }

  it should "keep the order of an object's keys when formatting" in {
    ok(run("format", """{"b":1,"a":2}""")).formatted shouldBe "{\n  \"b\": 1,\n  \"a\": 2\n}"
  }

  it should "be idempotent: formatting formatted output changes nothing" in {
    val once  = ok(run("format", """{"a":[1,{"b":2}],"c":{}}""")).formatted
    val twice = ok(run("format", once)).formatted
    twice shouldBe once
  }

  it should "return an error result for malformed JSON when formatting" in {
    failure(run("format", "{")) should startWith("Invalid JSON")
    failure(run("format", "[1,]")) should startWith("Invalid JSON")
  }

  it should "format a moderately deep document" in {
    val depth = 500
    val deep  = "[" * depth + "]" * depth
    val out   = ok(run("format", deep)).formatted
    out.count(_ == '[') shouldBe depth
    out.count(_ == ']') shouldBe depth
  }

  // ---- query

  it should "query a nested object key" in {
    val outcome = ok(run("query", """{"user":{"name":"Alice","age":30}}""", Some("user.name")))
    outcome.success shouldBe true
    outcome.result shouldBe ujson.Str("Alice")
  }

  it should "query array elements, including a leading index and mixed paths" in {
    ok(run("query", """{"items":[10,20,30]}""", Some("items[1]"))).result shouldBe ujson.Num(20)
    ok(run("query", "[[1,2],[3]]", Some("[1][0]"))).result shouldBe ujson.Num(3)
    ok(run("query", """{"d":{"us":[{"n":"x"},{"n":"y"}]}}""", Some("d.us[1].n"))).result shouldBe ujson.Str("y")
  }

  it should "accept a leading dot in a path" in {
    ok(run("query", """{"a":{"b":1}}""", Some(".a.b"))).result shouldBe ujson.Num(1)
  }

  it should "find keys that contain spaces" in {
    ok(run("query", """{"a b":1}""", Some("a b"))).result shouldBe ujson.Num(1)
  }

  it should "show a string result without quotes and any other result as JSON" in {
    ok(run("query", """{"u":{"n":"A"}}""", Some("u.n"))).formatted shouldBe "A"
    ok(run("query", """{"u":{"n":7}}""", Some("u.n"))).formatted shouldBe "7"
    ok(run("query", """{"u":{"n":7}}""", Some("u"))).formatted shouldBe "{\n  \"n\": 7\n}"
    ok(run("query", """{"a":[1,2]}""", Some("a"))).formatted shouldBe "[\n  1,\n  2\n]"
  }

  it should "report a JSON null as a successful result, not a missing key" in {
    val outcome = ok(run("query", """{"a":null}""", Some("a")))
    outcome.success shouldBe true
    outcome.result shouldBe ujson.Null
    outcome.formatted shouldBe "null"
  }

  it should "report a missing 'path' parameter" in {
    failure(run("query", """{"a":1}""")) should include("path")
  }

  it should "name the missing key" in {
    failure(run("query", """{"a":1}""", Some("b"))) should include("'b'")
  }

  it should "name the key it could not look up on an array, and the index it could not take from an object" in {
    failure(run("query", "[1]", Some("a"))) should include("'a'")
    failure(run("query", """{"a":1}""", Some("[0]"))) should include("0")
  }

  it should "name an out-of-bounds index" in {
    failure(run("query", "[1]", Some("[5]"))) should include("5")
    failure(run("query", """{"a":[1,2]}""", Some("a[2]"))) should include("2")
  }

  it should "return an error result for malformed JSON when querying" in {
    failure(run("query", "{", Some("a"))) should startWith("Invalid JSON")
  }

  // ---- validate

  it should "say whether a string is valid JSON, as a successful result either way" in {
    val valid = ok(run("validate", "[1]"))
    valid.success shouldBe true
    valid.result shouldBe ujson.True
    valid.formatted shouldBe "Valid JSON"

    Seq("[1", "", "{\"a\":}", "undefined").foreach { input =>
      val invalid = ok(run("validate", input))
      withClue(s"input '$input': ") {
        invalid.success shouldBe false
        invalid.result shouldBe ujson.False
        invalid.formatted shouldBe "Invalid JSON"
      }
    }
  }

  it should "accept the operation name in any letter case" in {
    ok(run("VALIDATE", "{}")).formatted shouldBe "Valid JSON"
    ok(run("Parse", "[1]")).result shouldBe ujson.Arr(1)
    ok(run("FoRmAt", "[]")).formatted shouldBe "[]"
    ok(run("QUERY", """{"a":1}""", Some("a"))).result shouldBe ujson.Num(1)
  }

  // ---- parameters

  it should "reject an unknown operation and list the supported ones" in {
    val err = failure(run("explode", "{}"))
    err should include("explode")
    List("parse", "format", "query", "validate").foreach(op => err should include(op))
  }

  it should "report a missing 'operation' or 'json' parameter by name" in {
    failure(run(ujson.Obj("json" -> "{}"))) should include("operation")
    failure(run(ujson.Obj("operation" -> "parse"))) should include("json")
    failure(run(ujson.Obj())) should include("operation")
  }

  it should "report a parameter of the wrong type by name" in {
    failure(run(ujson.Obj("operation" -> 5, "json" -> "{}"))) should include("operation")
    failure(run(ujson.Obj("operation" -> "parse", "json" -> 5))) should include("json")
  }

  // ---- through the public execute API

  it should "return the JSON result through execute for a success" in {
    val result = tool.execute(args("parse", "[1]")).fold(e => fail(s"Expected Right: ${e.getMessage}"), identity)
    result("success").bool shouldBe true
    result("result") shouldBe ujson.Arr(1)
    result("formatted").str shouldBe "[\n  1\n]"
  }

  it should "return a handler error through execute for malformed input" in {
    tool.execute(args("parse", "{")) match {
      case Left(ToolCallError.HandlerError(name, message)) =>
        name shouldBe "json_tool"
        message should startWith("Invalid JSON")
      case other => fail(s"Expected a HandlerError but got $other")
    }
  }

  // ---- behaviour the tool does not have yet

  // `parsePathParts` converts an array index with `String#toInt` outside any `Try`, so an index that does not fit in
  // an Int throws a NumberFormatException out of the handler (and out of `execute`) instead of returning a `Left`.
  it should "return an error result for an array index too large for an Int" in {
    pendingUntilFixed {
      val outcome = Try(run("query", """{"a":[1]}""", Some("a[99999999999]")))
      outcome.isSuccess shouldBe true
      outcome.get.isLeft shouldBe true
    }
  }

  // A path the parser cannot read to the end is cut short and the value reached so far is returned as a success:
  // `a..b`, `a[x]` and `a[-1]` all return the value of `a`. A caller asking for something else is told it succeeded.
  it should "not silently shorten a path it cannot parse completely" in {
    pendingUntilFixed {
      Seq("a..b", "a[x]", "a[-1]").foreach { path =>
        withClue(s"path $path: ") {
          run("query", """{"a":[1]}""", Some(path)).isLeft shouldBe true
        }
      }
    }
  }

  // `parse` and `format` pretty-print with `ujson.write(parsed, indent = 2)` outside the `Try` that guards the read.
  // The writer recurses once per nesting level, so a deeply nested document (a few thousand levels on a 1 MB stack;
  // `validate`, which does not write, copes with far more) raises a StackOverflowError, an Error that `Try` does not
  // catch, instead of returning a result. The call runs on its own thread with a fixed stack so the outcome does not
  // depend on the test runner's stack size, and the error is read from the thread's uncaught-exception handler.
  it should "not let a deeply nested document escape as an Error" in {
    pendingUntilFixed {
      val deep   = "[" * 20000 + "]" * 20000
      val thrown = new AtomicReference[Throwable](null)
      val thread = new Thread(
        null,
        () => {
          run("format", deep)
          ()
        },
        "json-tool-deep-nesting",
        1024L * 1024L
      )
      thread.setUncaughtExceptionHandler((_, e) => thrown.set(e))
      thread.start()
      thread.join(30000)
      thrown.get() shouldBe null
    }
  }
}
