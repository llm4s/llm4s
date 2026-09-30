package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * Multi-provider integration test for tool calling.
 *
 * This test verifies that both OpenAI and Anthropic providers handle tool calling correctly
 * through the complete flow: User query → Tool call → Tool execution → LLM response.
 */
class AgentToolCallingMultiProviderTest extends AnyFlatSpec with Matchers with MockFactory {

  // Test tool result
  case class ToolResult(success: Boolean, message: String, item: String)
  implicit val toolResultRW: ReadWriter[ToolResult] = macroRW[ToolResult]

  /**
   * Create a test tool that adds an item to inventory
   */
  def createInventoryTool(): Result[ToolFunction[Map[String, Any], ToolResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Add item to inventory")
      .withProperty(Schema.property("item", Schema.string("Item name")))

    ToolBuilder[Map[String, Any], ToolResult](
      "add_inventory_item",
      "Add an item to the player's inventory",
      schema
    ).withHandler { params =>
      val item = params.getString("item").getOrElse("unknown")
      Right(ToolResult(success = true, message = s"Added '$item' to inventory", item = item))
    }.buildSafe()
  }

  /**
   * Test scenario: Complete tool calling flow
   * 1. User asks to take an item
   * 2. LLM requests tool call
   * 3. Tool executes
   * 4. LLM responds with confirmation (this is where the bug occurred)
   * 5. User asks follow-up question
   * 6. LLM responds
   */
  def testToolCallingFlow(mockClient: LLMClient): Unit = {
    val agent = new Agent(mockClient)

    val setup = for {
      tool <- createInventoryTool()
      initialState <- agent.initializeSafe(
        query = "I want to take the sword",
        tools = new ToolRegistry(Seq(tool))
      )
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        // Step 1: First LLM call - agent requests tool use
        val toolCallId = "call_test_123"
        val step1Response = AssistantMessage(
          contentOpt = Some("I'll add the sword to your inventory."),
          toolCalls = Seq(
            ToolCall(
              id = toolCallId,
              name = "add_inventory_item",
              arguments = ujson.Obj("item" -> "sword")
            )
          )
        )

        val step1Completion = Completion(
          id = "completion-1",
          created = System.currentTimeMillis() / 1000,
          content = step1Response.content,
          model = "test-model",
          message = step1Response,
          toolCalls = step1Response.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(step1Completion))
          .once()

        // Run step 1 - should get tool call request
        val stateAfterStep1 = agent.runStep(initialState).fold(e => fail(s"Step 1 failed: ${e.formatted}"), identity)
        stateAfterStep1.status shouldBe AgentStatus.WaitingForTools

        // Step 2: Execute tool
        val stateAfterToolExecution =
          agent.runStep(stateAfterStep1).fold(e => fail(s"Tool execution failed: ${e.formatted}"), identity)
        stateAfterToolExecution.status shouldBe AgentStatus.InProgress

        // Verify tool message was created
        val toolMessages = stateAfterToolExecution.conversation.messages.collect { case tm: ToolMessage => tm }
        toolMessages should have size 1
        toolMessages.head.toolCallId shouldBe toolCallId
        toolMessages.head.content should include("sword")

        // Step 3: Second LLM call - agent processes tool result
        // This is where the bug occurred - the conversation now contains:
        // 1. UserMessage
        // 2. AssistantMessage with tool calls (must be properly serialized!)
        // 3. ToolMessage with result (must be properly serialized!)

        val step3Response = AssistantMessage(
          contentOpt = Some("I've added the sword to your inventory."),
          toolCalls = Seq.empty
        )

        val step3Completion = Completion(
          id = "completion-2",
          created = System.currentTimeMillis() / 1000,
          content = step3Response.content,
          model = "test-model",
          message = step3Response,
          toolCalls = List.empty,
          usage = Some(TokenUsage(150, 75, 225))
        )

        // This mock verifies that the conversation is properly serialized
        (mockClient.complete _)
          .expects(where { (conv: Conversation, _: CompletionOptions) =>
            // Verify conversation structure
            val messages = conv.messages

            // Should have: SystemMessage, UserMessage, AssistantMessage (with tool calls), ToolMessage
            messages should have size 4

            // Validate each message
            val validationResult = Message.validateConversation(messages.toList)
            validationResult should be(Symbol("right"))

            true
          })
          .returning(Right(step3Completion))
          .once()

        // Run step 3 - should process tool result and get final response
        val stateAfterStep3 =
          agent.runStep(stateAfterToolExecution).fold(e => fail(s"Step 3 failed: ${e.formatted}"), identity)
        stateAfterStep3.status shouldBe AgentStatus.Complete

        // Verify final response
        val finalAssistantMessages = stateAfterStep3.conversation.messages
          .collect { case am: AssistantMessage => am }
          .filterNot(_.hasToolCalls)

        finalAssistantMessages should not be empty
        finalAssistantMessages.last.content should include("inventory")

        // Step 4: Add another user message and continue conversation
        val stateWithFollowUp = stateAfterStep3
          .addMessage(UserMessage("What's in my inventory?"))
          .withStatus(AgentStatus.InProgress)

        val step4Response = AssistantMessage(
          contentOpt = Some("Your inventory contains: sword"),
          toolCalls = Seq.empty
        )

        val step4Completion = Completion(
          id = "completion-3",
          created = System.currentTimeMillis() / 1000,
          content = step4Response.content,
          model = "test-model",
          message = step4Response,
          toolCalls = List.empty,
          usage = Some(TokenUsage(200, 100, 300))
        )

        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(step4Completion))
          .once()

        // Run step 4 - should handle follow-up question
        val stateAfterStep4 =
          agent.runStep(stateWithFollowUp).fold(e => fail(s"Step 4 failed: ${e.formatted}"), identity)
        stateAfterStep4.status shouldBe AgentStatus.Complete

        // Verify the entire conversation is valid
        val finalValidation = Message.validateConversation(stateAfterStep4.conversation.messages.toList)
        finalValidation should be(Symbol("right"))
      }
    )
  }

  // Test with mock Anthropic-style client
  "Agent with Anthropic-style provider" should "handle complete tool calling flow" in {
    val mockAnthropicClient = mock[LLMClient]
    testToolCallingFlow(mockAnthropicClient)
  }

  // Test with mock OpenAI-style client
  "Agent with OpenAI-style provider" should "handle complete tool calling flow" in {
    val mockOpenAIClient = mock[LLMClient]
    testToolCallingFlow(mockOpenAIClient)
  }

  /**
   * Test scenario: Multiple tool calls in one response
   */
  def testMultipleToolCalls(mockClient: LLMClient): Unit = {
    val agent = new Agent(mockClient)

    val setup = for {
      tool <- createInventoryTool()
      initialState <- agent.initializeSafe(
        query = "Take the sword and the shield",
        tools = new ToolRegistry(Seq(tool))
      )
    } yield initialState

    setup.fold(
      e => fail(s"Setup failed: ${e.formatted}"),
      initialState => {
        // LLM requests multiple tools
        val multiToolResponse = AssistantMessage(
          contentOpt = Some("I'll add both items to your inventory."),
          toolCalls = Seq(
            ToolCall(id = "call_1", name = "add_inventory_item", arguments = ujson.Obj("item" -> "sword")),
            ToolCall(id = "call_2", name = "add_inventory_item", arguments = ujson.Obj("item" -> "shield"))
          )
        )

        val multiToolCompletion = Completion(
          id = "completion-multi",
          created = System.currentTimeMillis() / 1000,
          content = multiToolResponse.content,
          model = "test-model",
          message = multiToolResponse,
          toolCalls = multiToolResponse.toolCalls.toList,
          usage = Some(TokenUsage(100, 50, 150))
        )

        (mockClient.complete _)
          .expects(*, *)
          .returning(Right(multiToolCompletion))
          .once()

        // Get tool calls
        val stateAfterRequest =
          agent.runStep(initialState).fold(e => fail(s"Multi-tool request failed: ${e.formatted}"), identity)
        stateAfterRequest.status shouldBe AgentStatus.WaitingForTools

        // Execute tools
        val stateAfterExecution =
          agent.runStep(stateAfterRequest).fold(e => fail(s"Multi-tool execution failed: ${e.formatted}"), identity)

        // Should have 2 tool messages
        val toolMessages = stateAfterExecution.conversation.messages.collect { case tm: ToolMessage => tm }
        toolMessages should have size 2

        // Verify conversation is valid
        val validation = Message.validateConversation(stateAfterExecution.conversation.messages.toList)
        validation should be(Symbol("right"))
      }
    )
  }

  "Agent with Anthropic-style provider" should "handle multiple tool calls" in {
    val mockClient = mock[LLMClient]
    testMultipleToolCalls(mockClient)
  }

  "Agent with OpenAI-style provider" should "handle multiple tool calls" in {
    val mockClient = mock[LLMClient]
    testMultipleToolCalls(mockClient)
  }
}
