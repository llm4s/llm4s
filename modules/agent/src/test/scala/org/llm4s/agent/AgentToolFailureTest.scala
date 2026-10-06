package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * A failing tool never fails the run: the loop records exactly one non-empty, valid error result
 * for the call, the conversation stays valid, and the model answers next. Also the core
 * `ToolCallErrorJson` serialisation tools report their failures with.
 */
class AgentToolFailureTest extends AnyFlatSpec with Matchers {

  case class ToolResult(message: String)
  implicit val toolResultRW: ReadWriter[ToolResult] = macroRW[ToolResult]

  /** A tool with optional `item` and `quantity` whose handler fails with `errorMessage`. */
  def createFailingTool(name: String, errorMessage: String): Result[ToolFunction[Map[String, Any], ToolResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Tool parameters")
      .withProperty(Schema.property("item", Schema.string("Item name")))
      .withProperty(Schema.property("quantity", Schema.number("Quantity")))

    ToolBuilder[Map[String, Any], ToolResult](name, "Test tool that fails", schema)
      .withHandler(_ => Left(errorMessage))
      .buildSafe()
  }

  /** Runs one call of `tool` with `arguments`, then a final answer; returns the turn's result. */
  private def runFailing(
    tool: ToolFunction[?, ?],
    arguments: ujson.Value,
    callId: String = "call_123"
  ): AgentResult = {
    val call = ToolCall(callId, tool.name, arguments)
    val client = ScriptedLLMClient.of(
      CompletionFixture.withMessage(AssistantMessage(None, Seq(call))),
      CompletionFixture.simple("The tool failed, sorry.")
    )
    built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(tool)))).run("Use the tool").value
  }

  private def onlyToolMessage(result: AgentResult): ToolMessage = {
    val toolMessages = result.messages.collect { case tm: ToolMessage => tm }
    toolMessages should have size 1
    toolMessages.head
  }

  /** Arguments the failing tools' schema accepts, so their handler runs and fails. */
  private val validArgs = ujson.Obj("item" -> "apple", "quantity" -> 1)

  private def failing(name: String, error: String): ToolFunction[Map[String, Any], ToolResult] =
    createFailingTool(name, error).fold(e => fail(s"Setup failed: ${e.formatted}"), identity)

  "Agent" should "give a tool called with null arguments a non-empty error result, and complete" in {
    val result  = runFailing(failing("add_inventory_item", "unused"), ujson.Null)
    val message = onlyToolMessage(result)

    message.toolCallId shouldBe "call_123"
    message.content should not be empty
    message.validate.isRight shouldBe true
    result.answer shouldBe Some("The tool failed, sorry.")
    Message.validateConversation(result.messages.toList).isRight shouldBe true
  }

  it should "give a tool failing with an empty message a non-empty error result" in {
    val message = onlyToolMessage(runFailing(failing("empty_error_tool", ""), validArgs))

    message.content should not be empty
    message.validate.isRight shouldBe true
  }

  it should "keep an error's special characters, as valid JSON without double escaping" in {
    val specialError = "Error: \"user\" not found\nPath: C:\\Users\\test\tEnd"
    val message      = onlyToolMessage(runFailing(failing("special_json_tool", specialError), validArgs))

    val json = ujson.read(message.content)
    json("error").str should include("\"user\" not found")
    json("error").str should include("C:\\Users\\test")
  }

  it should "give a missing required argument an error result naming it, before the handler runs" in {
    val schema = Schema.`object`[Map[String, Any]]("Tool parameters").withRequiredField("query", Schema.string("Query"))
    val search = ToolBuilder[Map[String, Any], ToolResult]("search_tool", "Search for items", schema)
      .withHandler(extractor => extractor.getString("query").map(q => ToolResult(s"Found: $q")))
      .buildSafe()
      .fold(e => fail(e.formatted), identity)

    val message = onlyToolMessage(runFailing(search, ujson.Obj()))
    message.content should include("query")
    (message.content should not).include("Found:")
  }

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
