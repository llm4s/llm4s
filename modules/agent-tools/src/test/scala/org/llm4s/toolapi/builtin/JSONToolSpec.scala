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
 * Expected values are written out as literals; none is computed from the code under test. The last sections cover
 * what the tool refuses: array indexes too large for an `Int`, query paths it cannot read to the end, and documents
 * nested more than 512 levels deep.
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

  private def arrays(depth: Int): String  = "[" * depth + "]" * depth
  private def objects(depth: Int): String = "{\"a\":" * depth + "1" + "}" * depth

  /**
   * Run one operation on a thread with a fixed 1 MB stack, so that whether a deep document overflows the stack does
   * not depend on the test runner's stack size. An Error (a StackOverflowError is one, and `Try` does not catch it)
   * is read from the thread's uncaught-exception handler instead of aborting the suite.
   */
  private def onSmallStack(
    operation: String,
    json: String,
    path: Option[String] = None
  ): (Option[Either[String, JSONResult]], Option[Throwable]) = {
    val outcome = new AtomicReference[Either[String, JSONResult]](null)
    val thrown  = new AtomicReference[Throwable](null)
    val thread = new Thread(
      null,
      () => outcome.set(run(operation, json, path)),
      "json-tool-small-stack",
      1024L * 1024L
    )
    thread.setUncaughtExceptionHandler((_, e) => thrown.set(e))
    thread.start()
    thread.join(30000)
    (Option(outcome.get()), Option(thrown.get()))
  }

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

  // ---- array indexes

  it should "return an error result, not an exception, for an array index too large for an Int" in {
    Seq("99999999999", "2147483648").foreach { index =>
      val outcome = Try(run("query", """{"a":[1]}""", Some(s"a[$index]")))
      withClue(s"index $index: ") {
        outcome.isSuccess shouldBe true
        failure(outcome.get) should include(index)
      }
    }
  }

  it should "treat the largest Int as a legal index that is simply out of bounds" in {
    val outcome = Try(run("query", """{"a":[1]}""", Some("a[2147483647]")))
    outcome.isSuccess shouldBe true
    val message = failure(outcome.get)
    message should include("2147483647")
    (message should not).include("too large")
  }

  it should "still read an index with leading zeros as the number it spells" in {
    ok(run("query", "[10,20]", Some("[01]"))).result shouldBe ujson.Num(20)
  }

  // ---- paths that cannot be read to the end

  it should "not silently shorten a path it cannot parse completely, and name the text it stopped at" in {
    val shapes = Seq(
      "a..b"  -> "..b",
      "a[x]"  -> "[x]",
      "a[-1]" -> "[-1]",
      "a[]"   -> "[]",
      "a[1"   -> "[1",
      "a]"    -> "]",
      "a."    -> "'.'",
      "a.[0]" -> ".[0]",
      "."     -> "'.'",
      ".."    -> ".."
    )
    shapes.foreach { case (path, stoppedAt) =>
      val outcome = Try(run("query", """{"a":[1]}""", Some(path)))
      withClue(s"path $path: ") {
        outcome.isSuccess shouldBe true
        failure(outcome.get) should include(stoppedAt)
      }
    }
  }

  it should "report an unreadable path even when the part before it would not be found" in {
    failure(run("query", """{"a":1}""", Some("missing..b"))) should include("..b")
  }

  it should "report an unreadable path even for a document that is not an array or object" in {
    failure(run("query", "7", Some("a[x]"))) should include("[x]")
  }

  it should "read a key that contains a newline after a dot" in {
    ok(run("query", "{\"a\":{\"b\\nc\":1}}", Some("a.b\nc"))).result shouldBe ujson.Num(1)
  }

  // ---- nesting limit

  it should "accept a document nested exactly 512 levels deep in every operation" in {
    val doc = arrays(512)
    ok(run("parse", doc)).success shouldBe true
    ok(run("format", doc)).success shouldBe true
    ok(run("query", doc, Some(""))).success shouldBe true
    ok(run("validate", doc)).formatted shouldBe "Valid JSON"
  }

  it should "refuse a document nested 513 levels deep in every operation" in {
    val doc = arrays(513)
    Seq(
      "parse"    -> run("parse", doc),
      "format"   -> run("format", doc),
      "query"    -> run("query", doc, Some("")),
      "validate" -> run("validate", doc)
    ).foreach { case (operation, outcome) =>
      withClue(s"$operation: ") {
        failure(outcome) should include("512")
      }
    }
  }

  it should "count arrays and objects together towards the limit" in {
    val atLimit   = "[" * 256 + "{\"a\":" * 256 + "1" + "}" * 256 + "]" * 256
    val overLimit = "[" * 257 + "{\"a\":" * 256 + "1" + "}" * 256 + "]" * 257
    ok(run("parse", atLimit)).success shouldBe true
    failure(run("parse", overLimit)) should include("512")
  }

  it should "apply the limit to a document nested in objects as well as arrays" in {
    ok(run("format", objects(512))).success shouldBe true
    failure(run("format", objects(513))) should include("512")
  }

  it should "not count brackets inside string values towards the limit" in {
    val inString = "[\"" + "[" * 1000 + "\"]"
    ok(run("parse", inString)).result shouldBe ujson.Arr("[" * 1000)
    val escapedQuote = "[\"a\\\"" + "[" * 1000 + "\"]"
    ok(run("validate", escapedQuote)).formatted shouldBe "Valid JSON"
  }

  it should "end a string at a quote that follows an escaped backslash" in {
    // The text is ["x\\",[[[...]]]]: the string is `x\\`, so the brackets after it are real structure.
    val doc = "[\"x\\\\\"," + arrays(600) + "]"
    failure(run("parse", doc)) should include("512")
  }

  it should "refuse a very deeply nested document without an Error, in every operation" in {
    val doc = arrays(20000)
    Seq("parse", "format", "query", "validate").foreach { operation =>
      val (outcome, thrown) = onSmallStack(operation, doc, Some(""))
      withClue(s"$operation: ") {
        thrown shouldBe None
        outcome.map(_.isLeft) shouldBe Some(true)
      }
    }
  }

  it should "refuse a document with a million levels without reading it" in {
    val (outcome, thrown) = onSmallStack("parse", "[" * 1000000)
    thrown shouldBe None
    outcome.map(_.isLeft) shouldBe Some(true)
  }
}
