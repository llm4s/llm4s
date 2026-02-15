package org.llm4s.toolapi.builtin

import scala.annotation.nowarn

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CoreToolsSpec extends AnyFlatSpec with Matchers {

  "DateTimeTool" should "return current date/time in default timezone" in {
    val params = ujson.Obj()
    DateTimeTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            dateTime => {
              dateTime.timezone shouldBe "UTC"
              dateTime.iso8601.nonEmpty shouldBe true
              dateTime.components.year >= 2024 shouldBe true
            }
          )
    )
  }

  it should "support custom timezone" in {
    val params = ujson.Obj("timezone" -> "America/New_York")
    DateTimeTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            dateTime => dateTime.timezone shouldBe "America/New_York"
          )
    )
  }

  it should "return error for invalid timezone" in {
    val params = ujson.Obj("timezone" -> "Invalid/Timezone")
    DateTimeTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => err should include("timezone"),
            result => fail(s"Expected Left but got Right: $result")
          )
    )
  }

  "CalculatorTool" should "perform addition" in {
    val params = ujson.Obj("operation" -> "add", "a" -> 5.0, "b" -> 3.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 8.0
          )
    )
  }

  it should "perform subtraction" in {
    val params = ujson.Obj("operation" -> "subtract", "a" -> 10.0, "b" -> 4.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 6.0
          )
    )
  }

  it should "perform multiplication" in {
    val params = ujson.Obj("operation" -> "multiply", "a" -> 6.0, "b" -> 7.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 42.0
          )
    )
  }

  it should "perform division" in {
    val params = ujson.Obj("operation" -> "divide", "a" -> 15.0, "b" -> 3.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 5.0
          )
    )
  }

  it should "handle division by zero" in {
    val params = ujson.Obj("operation" -> "divide", "a" -> 10.0, "b" -> 0.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => err should include("zero"),
            result => fail(s"Expected Left but got Right: $result")
          )
    )
  }

  it should "perform square root" in {
    val params = ujson.Obj("operation" -> "sqrt", "a" -> 16.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 4.0
          )
    )
  }

  it should "handle negative square root" in {
    val params = ujson.Obj("operation" -> "sqrt", "a" -> -4.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => err should include("negative"),
            result => fail(s"Expected Left but got Right: $result")
          )
    )
  }

  it should "calculate power" in {
    val params = ujson.Obj("operation" -> "power", "a" -> 2.0, "b" -> 3.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 8.0
          )
    )
  }

  it should "calculate percentage" in {
    val params = ujson.Obj("operation" -> "percentage", "a" -> 200.0, "b" -> 15.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            calcResult => calcResult.result shouldBe 30.0
          )
    )
  }

  it should "reject unknown operation" in {
    val params = ujson.Obj("operation" -> "unknown", "a" -> 1.0)
    CalculatorTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => err should include("Unknown operation"),
            result => fail(s"Expected Left but got Right: $result")
          )
    )
  }

  "UUIDTool" should "generate a UUID" in {
    val params = ujson.Obj()
    UUIDTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            uuidResult => {
              uuidResult.uuids.size shouldBe 1
              uuidResult.uuids.head.uuid.length shouldBe 36
              uuidResult.uuids.head.version shouldBe 4
            }
          )
    )
  }

  it should "generate multiple UUIDs" in {
    val params = ujson.Obj("count" -> 5)
    UUIDTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            uuidResult => {
              uuidResult.uuids.size shouldBe 5
              uuidResult.uuids.map(_.uuid).distinct.size shouldBe 5 // All unique
            }
          )
    )
  }

  it should "generate standard format UUID" in {
    val params = ujson.Obj("format" -> "standard")
    UUIDTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            uuidResult => {
              uuidResult.uuids.head.uuid.length shouldBe 36
              uuidResult.uuids.head.uuid should include("-")
            }
          )
    )
  }

  it should "generate compact format UUID" in {
    val params = ujson.Obj("format" -> "compact")
    UUIDTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            uuidResult => {
              uuidResult.uuids.head.uuid.length shouldBe 32
              (uuidResult.uuids.head.uuid should not).include("-")
            }
          )
    )
  }

  "JSONTool" should "parse valid JSON" in {
    val params = ujson.Obj(
      "operation" -> "parse",
      "json"      -> """{"name": "test", "value": 42}"""
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            jsonResult => {
              jsonResult.success shouldBe true
              jsonResult.result.obj.nonEmpty shouldBe true
            }
          )
    )
  }

  it should "return error for invalid JSON" in {
    val params = ujson.Obj(
      "operation" -> "parse",
      "json"      -> """{"invalid": }"""
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => err should include("Invalid JSON"),
            result => fail(s"Expected Left but got Right: $result")
          )
    )
  }

  it should "format JSON with pretty printing" in {
    val params = ujson.Obj(
      "operation" -> "format",
      "json"      -> """{"a":1,"b":2}"""
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            jsonResult => {
              jsonResult.success shouldBe true
              jsonResult.formatted should include("\n")
            }
          )
    )
  }

  it should "query JSON with path" in {
    val params = ujson.Obj(
      "operation" -> "query",
      "json"      -> """{"user": {"name": "Alice", "age": 30}}""",
      "path"      -> "user.name"
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            jsonResult => {
              jsonResult.success shouldBe true
              jsonResult.formatted should include("Alice")
            }
          )
    )
  }

  it should "query array elements" in {
    val params = ujson.Obj(
      "operation" -> "query",
      "json"      -> """{"items": [1, 2, 3]}""",
      "path"      -> "items[1]"
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            jsonResult => {
              jsonResult.success shouldBe true
              jsonResult.formatted shouldBe "2"
            }
          )
    )
  }

  it should "validate JSON" in {
    val params = ujson.Obj(
      "operation" -> "validate",
      "json"      -> """{"valid": true}"""
    )
    JSONTool.toolSafe.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool =>
        tool
          .handler(SafeParameterExtractor(params))
          .fold(
            err => fail(s"Expected Right but got Left: $err"),
            jsonResult => {
              jsonResult.success shouldBe true
              jsonResult.formatted shouldBe "Valid JSON"
            }
          )
    )
  }

  // ============ Deprecated API tests ============

  "DateTimeTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = DateTimeTool.tool
    tool.name shouldBe "get_current_datetime"
  }

  "CalculatorTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = CalculatorTool.tool
    tool.name shouldBe "calculator"
  }

  "UUIDTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = UUIDTool.tool
    tool.name shouldBe "generate_uuid"
  }

  "JSONTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = JSONTool.tool
    tool.name shouldBe "json_tool"
  }

  "core.allTools (deprecated)" should "return all 4 core tools" in {
    @nowarn("cat=deprecation") val tools = core.allTools
    tools.size shouldBe 4
    val names = tools.map(_.name).toSet
    names should contain("get_current_datetime")
    names should contain("calculator")
    names should contain("generate_uuid")
    names should contain("json_tool")
  }
}
