package org.llm4s.agent

import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.collection.mutable.ArrayBuffer

class AgentToolFailureTest extends AnyFlatSpec with Matchers with MockFactory {

  // Result wrapper for tool response
  case class ToolResult(message: String)
  implicit val toolResultRW: ReadWriter[ToolResult] = macroRW[ToolResult]

  // Helper to create a failing tool
  def createFailingTool(name: String, errorMessage: String): Result[ToolFunction[Map[String, Any], ToolResult]] = {

    val schema = Schema
      .`object`[Map[String, Any]]("Tool parameters")
      .withProperty(Schema.property("item", Schema.string("Item name")))
      .withProperty(Schema.property("quantity", Schema.number("Quantity")))

    ToolBuilder[Map[String, Any], ToolResult](
      name,
      "Test tool that fails",
      schema
    ).withHandler { _ =>
      // Return the specified error
      Left(errorMessage)
    }.buildSafe()
  }

  "Agent" should "handle tool execution failures gracefully without creating empty messages" in {
    // Create a mock LLM client
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool <- createFailingTool("add_inventory_item", "Expected object at '' but found Null$")
      initialState <- agent.initializeSafe(
        query = "Add an apple to inventory",
        tools = new ToolRegistry(Seq(failingTool))
      )
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        // First interaction: LLM requests tool call with null arguments
        val toolCallId = "call_123"
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(
              id = toolCallId,
              name = "add_inventory_item",
              arguments = ujson.Null // This is the problematic null argument
            )
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-123",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        // Mock the first LLM call that returns a tool call request
        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(completionWithToolCall))
          .once()

        // Run the first step - should get tool call
        val step1Result = agent.runStep(initialState)
        (step1Result should be).a(Symbol("right"))

        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        stateAfterToolCall.status shouldBe AgentStatus.WaitingForTools

        // Run the next step - process the tool call (which will fail)
        val step2Result = agent.runStep(stateAfterToolCall)
        (step2Result should be).a(Symbol("right"))

        val stateAfterToolExecution = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        // Verify the tool message was created with error content (not empty)
        val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }

        toolMessages should have size 1
        val toolMessage = toolMessages.head
        toolMessage.toolCallId shouldBe toolCallId
        toolMessage.content should not be empty
        toolMessage.content should include("isError")
        toolMessage.content should include("Tool call 'add_inventory_item'")
        toolMessage.content should include("received null arguments")

        // Verify the message passes validation
        (toolMessage.validate should be).a(Symbol("right"))

        // The state should be ready to continue (InProgress)
        stateAfterToolExecution.status shouldBe AgentStatus.InProgress

        // Verify the conversation is valid
        val validationResult = Message.validateConversation(stateAfterToolExecution.conversation.messages.toList)
        (validationResult should be).a(Symbol("right"))
      }
    )
  }

  "Agent" should "handle tool execution with empty error messages" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool <- createFailingTool("test_tool", "")
      initialState <- agent.initializeSafe(
        query = "Test query",
        tools = new ToolRegistry(Seq(failingTool))
      )
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val toolCallId = "call_456"
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = Some("I'll use the test tool"),
          toolCalls = Seq(
            ToolCall(
              id = toolCallId,
              name = "test_tool",
              arguments = ujson.Obj()
            )
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-123",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(completionWithToolCall))
          .once()

        // Run steps
        val step1Result = agent.runStep(initialState)
        (step1Result should be).a(Symbol("right"))

        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        (step2Result should be).a(Symbol("right"))

        val stateAfterToolExecution = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        // Verify tool message has non-empty content even with empty error
        val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }

        toolMessages should have size 1
        val toolMessage = toolMessages.head
        toolMessage.content should not be empty
        toolMessage.content should include("isError")

        // Verify message and conversation validation
        (toolMessage.validate should be).a(Symbol("right"))
        (Message.validateConversation(stateAfterToolExecution.conversation.messages.toList) should be).a(
          Symbol("right")
        )
      }
    )
  }

  "Agent" should "handle tool execution errors with special characters" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool <- createFailingTool("special_char_tool", "Error with \"quotes\" and \\ backslash")
      initialState <- agent.initializeSafe(
        query = "Test special characters",
        tools = new ToolRegistry(Seq(failingTool))
      )
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(
              id = "call_789",
              name = "special_char_tool",
              arguments = ujson.Obj()
            )
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-123",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(completionWithToolCall))
          .once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val step2Result             = agent.runStep(stateAfterToolCall)
        val stateAfterToolExecution = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        // Verify tool message content is valid JSON-like structure
        val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }

        toolMessages should have size 1
        val toolMessage = toolMessages.head
        toolMessage.content should not be empty

        // The error message should be properly escaped in the JSON
        toolMessage.content should include("isError")

        // Verify validation passes
        (toolMessage.validate should be).a(Symbol("right"))
        (Message.validateConversation(stateAfterToolExecution.conversation.messages.toList) should be).a(
          Symbol("right")
        )
      }
    )
  }

  // ============================================================================
  // Structured JSON Error Payload Tests
  // ============================================================================

  "Agent" should "produce structured JSON with errorType null_arguments" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    // Create a tool that requires parameters
    val schema = Schema
      .`object`[Map[String, Any]]("Tool parameters")
      .withRequiredField("query", Schema.string("Search query"))

    val setup = for {
      searchTool <- ToolBuilder[Map[String, Any], ToolResult](
        "search_tool",
        "Search for items",
        schema
      ).withHandler(extractor => extractor.getString("query").map(q => ToolResult(s"Found: $q"))).buildSafe()
      initialState <- agent.initializeSafe(query = "Search for apples", tools = new ToolRegistry(Seq(searchTool)))
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(id = "call_null", name = "search_tool", arguments = ujson.Null)
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-null",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _).expects(*, *).returning(Right(completionWithToolCall)).once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        val stateAfterExec     = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val toolMessages = stateAfterExec.conversation.messages.collect { case tm: ToolMessage => tm }
        toolMessages should have size 1

        val json = ujson.read(toolMessages.head.content)

        // Validate structured JSON fields
        json("isError").bool shouldBe true
        json("toolName").str shouldBe "search_tool"
        json("errorType").str shouldBe "null_arguments"
        json("message").str should include("null arguments")
        json("error").str should include("Tool call 'search_tool'") // Legacy field
      }
    )
  }

  "Agent" should "produce structured JSON with errorType handler_error" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool  <- createFailingTool("database_tool", "Connection timeout after 30s")
      initialState <- agent.initializeSafe(query = "Query database", tools = new ToolRegistry(Seq(failingTool)))
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(id = "call_handler", name = "database_tool", arguments = ujson.Obj("item" -> "test"))
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-handler",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _).expects(*, *).returning(Right(completionWithToolCall)).once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        val stateAfterExec     = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val toolMessages = stateAfterExec.conversation.messages.collect { case tm: ToolMessage => tm }
        toolMessages should have size 1

        val json = ujson.read(toolMessages.head.content)

        // Validate structured JSON fields
        json("isError").bool shouldBe true
        json("toolName").str shouldBe "database_tool"
        json("errorType").str shouldBe "handler_error"
        json("message").str should include("Connection timeout after 30s")
        json("error").str should include("Tool call 'database_tool'")
      }
    )
  }

  "Agent" should "always include legacy error field for backward compatibility" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool  <- createFailingTool("legacy_test_tool", "Test error")
      initialState <- agent.initializeSafe(query = "Test", tools = new ToolRegistry(Seq(failingTool)))
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(id = "call_legacy", name = "legacy_test_tool", arguments = ujson.Obj())
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-legacy",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _).expects(*, *).returning(Right(completionWithToolCall)).once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        val stateAfterExec     = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val toolMessages = stateAfterExec.conversation.messages.collect { case tm: ToolMessage => tm }
        val json         = ujson.read(toolMessages.head.content)

        // Legacy error field must always be present
        json.obj.contains("error") shouldBe true
        json("error").str should not be empty
        json("error").str should include("Tool call")
      }
    )
  }

  "Agent" should "produce valid JSON without double-escaping special characters" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    // Error with quotes, newlines, backslashes, tabs
    val specialError = "Error: \"user\" not found\nPath: C:\\Users\\test\tEnd"

    val setup = for {
      failingTool  <- createFailingTool("special_json_tool", specialError)
      initialState <- agent.initializeSafe(query = "Test", tools = new ToolRegistry(Seq(failingTool)))
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(id = "call_special", name = "special_json_tool", arguments = ujson.Obj())
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-special",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _).expects(*, *).returning(Right(completionWithToolCall)).once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        val stateAfterExec     = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val toolMessages = stateAfterExec.conversation.messages.collect { case tm: ToolMessage => tm }
        val content      = toolMessages.head.content

        // Must be valid JSON (no parse errors)
        val parseResult = scala.util.Try(ujson.read(content))
        parseResult.isSuccess shouldBe true

        val json = parseResult.get
        // The message field should contain the special characters properly
        json("message").str should include("\"user\"")
        json("message").str should include("not found")
      }
    )
  }

  "Agent" should "pass ToolMessage validation with structured error content" in {
    val mockClient = mock[LLMClient]
    val agent      = new Agent(mockClient)

    val setup = for {
      failingTool  <- createFailingTool("validation_tool", "Validation test error")
      initialState <- agent.initializeSafe(query = "Test", tools = new ToolRegistry(Seq(failingTool)))
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        val assistantResponseWithToolCall = AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall(id = "call_validate", name = "validation_tool", arguments = ujson.Obj())
          )
        )

        val completionWithToolCall = Completion(
          id = "completion-validate",
          created = System.currentTimeMillis() / 1000,
          content = assistantResponseWithToolCall.content,
          model = "test-model",
          message = assistantResponseWithToolCall,
          toolCalls = assistantResponseWithToolCall.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _).expects(*, *).returning(Right(completionWithToolCall)).once()

        val step1Result        = agent.runStep(initialState)
        val stateAfterToolCall = step1Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)
        val step2Result        = agent.runStep(stateAfterToolCall)
        val stateAfterExec     = step2Result.fold(e => fail(s"Expected successful step: ${e.formatted}"), identity)

        val toolMessages = stateAfterExec.conversation.messages.collect { case tm: ToolMessage => tm }

        // All messages should pass validation
        toolMessages.foreach(msg => (msg.validate should be).a(Symbol("right")))

        // Conversation should also be valid
        (Message.validateConversation(stateAfterExec.conversation.messages.toList) should be).a(Symbol("right"))
      }
    )
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
    json("message").str shouldBe "is not a recognized tool"
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

  // ============================================================================
  // Test for streaming events with tool failure (covers Agent line 1383)
  // ============================================================================

  /**
   * Simple mock LLMClient for testing tool failures with streaming events.
   */
  class StreamingMockLLMClient(responses: Seq[Either[org.llm4s.error.APIError, Completion]]) extends LLMClient {

    private var callIndex = 0

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Either[org.llm4s.error.APIError, Completion] = {
      val result = if (callIndex < responses.size) responses(callIndex) else responses.last
      callIndex += 1
      result
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Either[org.llm4s.error.APIError, Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "Agent.runWithEvents" should "emit ToolCallFailed event when tool execution fails" in {
    createFailingTool("failing_stream_tool", "Simulated streaming failure").fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      failingTool => {
        val toolRegistry = new ToolRegistry(Seq(failingTool))

        // Create tool call response
        val toolCall = ToolCall(
          id = "stream_call_001",
          name = "failing_stream_tool",
          arguments = ujson.Obj("item" -> "test", "quantity" -> 1)
        )
        val toolCallResponse = AssistantMessage(
          contentOpt = Some("Let me use the tool"),
          toolCalls = Seq(toolCall)
        )
        val completion1 = Completion(
          id = "comp-1",
          created = System.currentTimeMillis() / 1000,
          content = toolCallResponse.content,
          model = "test-model",
          message = toolCallResponse,
          toolCalls = List(toolCall),
          usage = Some(TokenUsage(10, 10, 20))
        )

        // Final response after tool error
        val finalResponse = AssistantMessage(contentOpt = Some("Tool failed, sorry."))
        val completion2 = Completion(
          id = "comp-2",
          created = System.currentTimeMillis() / 1000,
          content = finalResponse.content,
          model = "test-model",
          message = finalResponse,
          toolCalls = Nil,
          usage = Some(TokenUsage(10, 10, 20))
        )

        val mockClient = new StreamingMockLLMClient(Seq(Right(completion1), Right(completion2)))
        val agent      = new Agent(mockClient)

        val events = ArrayBuffer[AgentEvent]()

        val result = agent.runWithEvents(
          query = "Use the failing tool",
          tools = toolRegistry,
          onEvent = events += _
        )

        result.isRight shouldBe true

        // Should have received ToolCallFailed event
        val failedEvents = events.collect { case e: AgentEvent.ToolCallFailed => e }
        failedEvents should have size 1
        failedEvents.head.toolName shouldBe "failing_stream_tool"
        failedEvents.head.error should include("isError")
      }
    )
  }
}
