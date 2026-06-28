package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class ToolCallErrorJsonSpec extends AnyFlatSpec with Matchers {

  // ---- ToolParameterError.MultipleErrors ----

  "ToolParameterError.MultipleErrors" should "delegate getMessage to single error when list has one element" in {
    val inner = ToolParameterError.MissingParameter("email", "string")
    val multi = ToolParameterError.MultipleErrors(List(inner))
    multi.getMessage shouldBe inner.getMessage
  }

  it should "produce a combined message for multiple errors" in {
    val errors = List(
      ToolParameterError.MissingParameter("a", "string"),
      ToolParameterError.TypeMismatch("b", "integer", "string")
    )
    val multi = ToolParameterError.MultipleErrors(errors)
    multi.getMessage should include("multiple parameter issues")
    multi.getMessage should include("'a'")
    multi.getMessage should include("'b'")
  }

  it should "report all parameter names joined" in {
    val errors = List(
      ToolParameterError.MissingParameter("x", "string"),
      ToolParameterError.NullParameter("y", "integer")
    )
    ToolParameterError.MultipleErrors(errors).parameterName shouldBe "x, y"
  }

  // ---- ToolCallError.isRetryable ----

  "ToolCallError.isRetryable" should "return false for UnknownFunction" in {
    ToolCallError.isRetryable(ToolCallError.UnknownFunction("f")) shouldBe false
  }

  it should "return false for NullArguments" in {
    ToolCallError.isRetryable(ToolCallError.NullArguments("f")) shouldBe false
  }

  it should "return false for InvalidArguments" in {
    ToolCallError.isRetryable(
      ToolCallError.InvalidArguments("f", List(ToolParameterError.MissingParameter("p", "string")))
    ) shouldBe false
  }

  it should "return false for HandlerError" in {
    ToolCallError.isRetryable(ToolCallError.HandlerError("f", "err")) shouldBe false
  }

  it should "return true for Timeout" in {
    ToolCallError.isRetryable(ToolCallError.Timeout("f", 30.seconds)) shouldBe true
  }

  it should "return true for ExecutionError caused by IOException" in {
    val err = ToolCallError.ExecutionError("f", new java.io.IOException("network"))
    ToolCallError.isRetryable(err) shouldBe true
  }

  it should "return true for ExecutionError caused by TimeoutException" in {
    val err = ToolCallError.ExecutionError("f", new java.util.concurrent.TimeoutException("slow"))
    ToolCallError.isRetryable(err) shouldBe true
  }

  it should "return false for ExecutionError caused by a non-transient exception" in {
    val err = ToolCallError.ExecutionError("f", new RuntimeException("bug"))
    ToolCallError.isRetryable(err) shouldBe false
  }

  // ---- ToolCallError.Timeout formatting ----

  "ToolCallError.Timeout" should "include duration in its message" in {
    val t = ToolCallError.Timeout("slow_tool", 10.seconds)
    t.getFormattedMessage should include("slow_tool")
    t.getFormattedMessage should include("10")
  }

  // ---- ToolCallErrorJson.flattenErrors ----

  "ToolCallErrorJson.flattenErrors" should "pass through a flat list unchanged" in {
    val errors = List(
      ToolParameterError.MissingParameter("a", "string"),
      ToolParameterError.TypeMismatch("b", "integer", "number")
    )
    ToolCallErrorJson.flattenErrors(errors) shouldBe errors
  }

  it should "inline nested MultipleErrors" in {
    val inner = List(
      ToolParameterError.MissingParameter("x", "string"),
      ToolParameterError.NullParameter("y", "integer")
    )
    val errors = List(ToolParameterError.MultipleErrors(inner))
    ToolCallErrorJson.flattenErrors(errors) shouldBe inner
  }

  it should "recursively flatten deeply nested MultipleErrors" in {
    val leaf  = ToolParameterError.MissingParameter("z", "boolean")
    val inner = ToolParameterError.MultipleErrors(List(leaf))
    val outer = ToolParameterError.MultipleErrors(List(inner))
    ToolCallErrorJson.flattenErrors(List(outer)) shouldBe List(leaf)
  }

  // ---- ToolCallErrorJson.parameterErrorToJson ----

  "ToolCallErrorJson.parameterErrorToJson" should "serialize MissingParameter with availableParameters" in {
    val err = ToolParameterError.MissingParameter("name", "string", List("age", "email"))
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "missing_parameter"
    obj("parameterName").str shouldBe "name"
    obj("expectedType").str shouldBe "string"
    obj("availableParameters").arr.map(_.str).toList shouldBe List("age", "email")
  }

  it should "serialize MissingParameter without availableParameters when list is empty" in {
    val err = ToolParameterError.MissingParameter("name", "string")
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "missing_parameter"
    obj.obj.contains("availableParameters") shouldBe false
  }

  it should "serialize NullParameter" in {
    val err = ToolParameterError.NullParameter("age", "integer")
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "null_parameter"
    obj("receivedType").str shouldBe "null"
  }

  it should "serialize TypeMismatch" in {
    val err = ToolParameterError.TypeMismatch("count", "integer", "string")
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "type_mismatch"
    obj("expectedType").str shouldBe "integer"
    obj("receivedType").str shouldBe "string"
  }

  it should "serialize InvalidNesting" in {
    val err = ToolParameterError.InvalidNesting("name", "user", "string")
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "invalid_nesting"
    obj("parentPath").str shouldBe "user"
    obj("receivedType").str shouldBe "string"
  }

  it should "serialize MultipleErrors gracefully" in {
    val err = ToolParameterError.MultipleErrors(
      List(ToolParameterError.MissingParameter("a", "string"))
    )
    val obj = ToolCallErrorJson.parameterErrorToJson(err)
    obj("kind").str shouldBe "multiple_errors"
  }

  // ---- ToolCallErrorJson.toJson ----

  "ToolCallErrorJson.toJson" should "include isError=true and toolName for all error types" in {
    val errors: List[ToolCallError] = List(
      ToolCallError.UnknownFunction("f"),
      ToolCallError.NullArguments("f"),
      ToolCallError.InvalidArguments("f", List(ToolParameterError.MissingParameter("p", "string"))),
      ToolCallError.HandlerError("f", "bad"),
      ToolCallError.ExecutionError("f", new RuntimeException("boom")),
      ToolCallError.Timeout("f", 5.seconds)
    )
    errors.foreach { err =>
      val json = ToolCallErrorJson.toJson(err)
      json("isError").bool shouldBe true
      json("toolName").str shouldBe "f"
    }
  }

  it should "set errorType=unknown_function for UnknownFunction" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.UnknownFunction("f"))
    json("errorType").str shouldBe "unknown_function"
  }

  it should "set errorType=null_arguments for NullArguments" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.NullArguments("f"))
    json("errorType").str shouldBe "null_arguments"
  }

  it should "set errorType=invalid_arguments and populate parameterErrors" in {
    val err  = ToolCallError.InvalidArguments("f", List(ToolParameterError.TypeMismatch("x", "string", "integer")))
    val json = ToolCallErrorJson.toJson(err)
    json("errorType").str shouldBe "invalid_arguments"
    (json("parameterErrors").arr should have).length(1)
  }

  it should "set errorType=handler_error for HandlerError" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.HandlerError("f", "oops"))
    json("errorType").str shouldBe "handler_error"
  }

  it should "set errorType=execution_error and include exceptionType" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.ExecutionError("f", new IllegalArgumentException("bad")))
    json("errorType").str shouldBe "execution_error"
    json("exceptionType").str shouldBe "IllegalArgumentException"
  }

  it should "set errorType=timeout and include duration for Timeout" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.Timeout("f", 30.seconds))
    json("errorType").str shouldBe "timeout"
    json("code").str shouldBe "timeout"
    json("duration").str should include("30")
  }

  it should "always include legacy error field" in {
    val json = ToolCallErrorJson.toJson(ToolCallError.UnknownFunction("myTool"))
    json.obj.contains("error") shouldBe true
    json("error").str should include("myTool")
  }
}
