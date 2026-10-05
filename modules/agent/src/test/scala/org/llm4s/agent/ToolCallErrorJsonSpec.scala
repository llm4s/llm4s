package org.llm4s.agent

import org.llm4s.toolapi._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests of core's `ToolCallErrorJson`, the structured JSON form of a tool call's error
 * (`errorType`, `parameterErrors`, and the legacy `error` field).
 *
 * The agent no longer puts this JSON in its tool results: the tool loop records a failed call as
 * `{"error": "<message>"}`, and reports invalid arguments from its own argument validation. These
 * tests stay because the class is public and other callers format errors with it.
 */
class ToolCallErrorJsonSpec extends AnyFlatSpec with Matchers {

  // ============================================================================
  // ToolCallErrorJson Unit Tests (direct serialization)
  // ============================================================================

  "ToolCallErrorJson" should "serialize UnknownFunction correctly" in {
    val error = ToolCallError.UnknownFunction("nonexistent_tool")
    val json  = ToolCallErrorJson.toJson(error)

    json("isError").bool shouldBe true
    json("toolName").str shouldBe "nonexistent_tool"
    json("errorType").str shouldBe "unknown_function"
    json("message").str should include("is not a recognized tool")
    json("error").str should include("Tool call 'nonexistent_tool'")
    json.obj.get("parameterErrors") shouldBe None
  }

  "ToolCallErrorJson" should "serialize NullArguments correctly" in {
    val error = ToolCallError.NullArguments("my_tool")
    val json  = ToolCallErrorJson.toJson(error)

    json("isError").bool shouldBe true
    json("toolName").str shouldBe "my_tool"
    json("errorType").str shouldBe "null_arguments"
    json("message").str should include("null arguments")
  }

  "ToolCallErrorJson" should "serialize InvalidArguments with parameterErrors" in {
    val paramErrors = List(
      ToolParameterError.MissingParameter("query", "string", List("q", "search")),
      ToolParameterError.TypeMismatch("count", "integer", "string")
    )
    val error = ToolCallError.InvalidArguments("search_tool", paramErrors)
    val json  = ToolCallErrorJson.toJson(error)

    json("isError").bool shouldBe true
    json("toolName").str shouldBe "search_tool"
    json("errorType").str shouldBe "invalid_arguments"
    json("parameterErrors").arr should have size 2

    // First parameter error (MissingParameter)
    val pe0 = json("parameterErrors")(0)
    pe0("parameterName").str shouldBe "query"
    pe0("kind").str shouldBe "missing_parameter"
    pe0("expectedType").str shouldBe "string"
    pe0("receivedType") shouldBe ujson.Null
    (pe0("availableParameters").arr.map(_.str) should contain).allOf("q", "search")

    // Second parameter error (TypeMismatch)
    val pe1 = json("parameterErrors")(1)
    pe1("parameterName").str shouldBe "count"
    pe1("kind").str shouldBe "type_mismatch"
    pe1("expectedType").str shouldBe "integer"
    pe1("receivedType").str shouldBe "string"
  }

  "ToolCallErrorJson" should "serialize HandlerError correctly" in {
    val error = ToolCallError.HandlerError("api_tool", "API rate limit exceeded")
    val json  = ToolCallErrorJson.toJson(error)

    json("isError").bool shouldBe true
    json("toolName").str shouldBe "api_tool"
    json("errorType").str shouldBe "handler_error"
    json("message").str should include("API rate limit exceeded")
    json.obj.get("parameterErrors") shouldBe None
  }

  "ToolCallErrorJson" should "serialize ExecutionError with exceptionType" in {
    val error = ToolCallError.ExecutionError("crash_tool", new RuntimeException("Out of memory"))
    val json  = ToolCallErrorJson.toJson(error)

    json("isError").bool shouldBe true
    json("toolName").str shouldBe "crash_tool"
    json("errorType").str shouldBe "execution_error"
    json("exceptionType").str shouldBe "RuntimeException"
    json("message").str should include("Out of memory")
  }

  "ToolCallErrorJson" should "flatten MultipleErrors into parameterErrors array" in {
    val nested = ToolParameterError.MultipleErrors(
      List(
        ToolParameterError.MissingParameter("param1", "string"),
        ToolParameterError.MissingParameter("param2", "integer")
      )
    )
    val error = ToolCallError.InvalidArguments("multi_tool", List(nested))
    val json  = ToolCallErrorJson.toJson(error)

    json("parameterErrors").arr should have size 2
    json("parameterErrors")(0)("parameterName").str shouldBe "param1"
    json("parameterErrors")(1)("parameterName").str shouldBe "param2"
  }

  "ToolCallErrorJson" should "handle NullParameter correctly" in {
    val paramErrors = List(ToolParameterError.NullParameter("name", "string"))
    val error       = ToolCallError.InvalidArguments("null_param_tool", paramErrors)
    val json        = ToolCallErrorJson.toJson(error)

    json("parameterErrors").arr should have size 1
    val pe = json("parameterErrors")(0)
    pe("parameterName").str shouldBe "name"
    pe("kind").str shouldBe "null_parameter"
    pe("expectedType").str shouldBe "string"
    pe("receivedType").str shouldBe "null"
  }

  "ToolCallErrorJson" should "handle InvalidNesting correctly" in {
    val paramErrors = List(ToolParameterError.InvalidNesting("child", "parent", "array"))
    val error       = ToolCallError.InvalidArguments("nested_tool", paramErrors)
    val json        = ToolCallErrorJson.toJson(error)

    json("parameterErrors").arr should have size 1
    val pe = json("parameterErrors")(0)
    pe("parameterName").str shouldBe "child"
    pe("kind").str shouldBe "invalid_nesting"
    pe("expectedType").str shouldBe "object"
    pe("receivedType").str shouldBe "array"
    pe("parentPath").str shouldBe "parent"
  }

  // ============================================================================
  // Additional tests for coverage completeness
  // ============================================================================

  "ToolCallErrorJson" should "handle MissingParameter without available parameters" in {
    // Test case: MissingParameter with empty availableParameters list
    val paramErrors = List(ToolParameterError.MissingParameter("username", "string", Nil))
    val error       = ToolCallError.InvalidArguments("user_tool", paramErrors)
    val json        = ToolCallErrorJson.toJson(error)

    json("parameterErrors").arr should have size 1
    val pe = json("parameterErrors")(0)
    pe("parameterName").str shouldBe "username"
    pe("kind").str shouldBe "missing_parameter"
    pe("expectedType").str shouldBe "string"
    pe("receivedType") shouldBe ujson.Null
    // Should NOT have availableParameters field when list is empty
    pe.obj.get("availableParameters") shouldBe None
  }

  "ToolCallErrorJson.parameterErrorToJson" should "handle MultipleErrors directly" in {
    // Tests the fallback case in parameterErrorToJson for MultipleErrors
    // This case shouldn't normally occur (errors are flattened), but should handle gracefully
    val multiError = ToolParameterError.MultipleErrors(
      List(
        ToolParameterError.MissingParameter("field1", "string"),
        ToolParameterError.TypeMismatch("field2", "integer", "boolean")
      )
    )
    val json = ToolCallErrorJson.parameterErrorToJson(multiError)

    json("parameterName").str shouldBe "field1, field2"
    json("kind").str shouldBe "multiple_errors"
  }

  "MissingParameter.getMessage" should "include available parameters when present" in {
    val error = ToolParameterError.MissingParameter("query", "string", List("q", "search", "term"))
    error.getMessage should include("available: q, search, term")
  }

  "MissingParameter.getMessage" should "not include available hint when list is empty" in {
    val error = ToolParameterError.MissingParameter("query", "string", Nil)
    error.getMessage shouldBe "required parameter 'query' (type: string) is missing"
    (error.getMessage should not).include("available")
  }
}
