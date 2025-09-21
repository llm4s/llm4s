package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class AgentToolFailureTest extends AnyFlatSpec with Matchers with MockFactory {

  // Result wrapper for tool response
  case class ToolResult(message: String)
  implicit val toolResultRW: ReadWriter[ToolResult] = macroRW[ToolResult]

  // Helper to create a failing tool
  def createFailingTool(name: String, errorMessage: String): ToolFunction[Map[String, Any], ToolResult] = {

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
    }.build()
  }

  "Agent" should "handle tool execution failures gracefully without creating empty messages" in {
    // Create a mock LLM client
    val mockClient = mock[LLMClient]

    // Create a tool that fails with the error from the trace
    val failingTool = createFailingTool("add_inventory_item", "Expected object at '' but found Null$")

    val toolRegistry = new ToolRegistry(Seq(failingTool))
    val agent        = new Agent(mockClient)

    // Initialize agent state
    val initialState = agent.initialize(
      query = "Add an apple to inventory",
      tools = toolRegistry
    )

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

    val stateAfterToolCall = step1Result.getOrElse(fail("Expected successful step"))
    stateAfterToolCall.status shouldBe AgentStatus.WaitingForTools

    // Run the next step - process the tool call (which will fail)
    val step2Result = agent.runStep(stateAfterToolCall)
    (step2Result should be).a(Symbol("right"))

    val stateAfterToolExecution = step2Result.getOrElse(fail("Expected successful step"))

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

  "Agent" should "handle tool execution with empty error messages" in {
    val mockClient = mock[LLMClient]

    // Create a tool that fails with an empty error message
    val failingTool = createFailingTool("test_tool", "")

    val toolRegistry = new ToolRegistry(Seq(failingTool))
    val agent        = new Agent(mockClient)

    val initialState = agent.initialize(
      query = "Test query",
      tools = toolRegistry
    )

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

    val stateAfterToolCall = step1Result.getOrElse(fail("Expected successful step"))
    val step2Result        = agent.runStep(stateAfterToolCall)
    (step2Result should be).a(Symbol("right"))

    val stateAfterToolExecution = step2Result.getOrElse(fail("Expected successful step"))

    // Verify tool message has non-empty content even with empty error
    val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }

    toolMessages should have size 1
    val toolMessage = toolMessages.head
    toolMessage.content should not be empty
    toolMessage.content should include("isError")

    // Verify message and conversation validation
    (toolMessage.validate should be).a(Symbol("right"))
    (Message.validateConversation(stateAfterToolExecution.conversation.messages.toList) should be).a(Symbol("right"))
  }

  "Agent" should "handle tool execution errors with special characters" in {
    val mockClient = mock[LLMClient]

    // Create a tool that fails with special characters in error message
    val failingTool = createFailingTool("special_char_tool", "Error with \"quotes\" and \\ backslash")

    val toolRegistry = new ToolRegistry(Seq(failingTool))
    val agent        = new Agent(mockClient)

    val initialState = agent.initialize(
      query = "Test special characters",
      tools = toolRegistry
    )

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
    val stateAfterToolCall = step1Result.getOrElse(fail("Expected successful step"))

    val step2Result             = agent.runStep(stateAfterToolCall)
    val stateAfterToolExecution = step2Result.getOrElse(fail("Expected successful step"))

    // Verify tool message content is valid JSON-like structure
    val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }

    toolMessages should have size 1
    val toolMessage = toolMessages.head
    toolMessage.content should not be empty

    // The error message should be properly escaped in the JSON
    toolMessage.content should include("isError")

    // Verify validation passes
    (toolMessage.validate should be).a(Symbol("right"))
    (Message.validateConversation(stateAfterToolExecution.conversation.messages.toList) should be).a(Symbol("right"))
  }
}
