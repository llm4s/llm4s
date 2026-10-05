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

  private def completion(id: String, message: AssistantMessage, usage: TokenUsage): Completion =
    Completion(
      id = id,
      created = System.currentTimeMillis() / 1000,
      content = message.content,
      model = "test-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(usage)
    )

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
    val tools =
      createInventoryTool().fold(e => fail(s"Setup failed: ${e.formatted}"), tool => new ToolRegistry(Seq(tool)))

    val toolCallId = "call_test_123"
    val step1Response = AssistantMessage(
      contentOpt = Some("I'll add the sword to your inventory."),
      toolCalls = Seq(ToolCall(id = toolCallId, name = "add_inventory_item", arguments = ujson.Obj("item" -> "sword")))
    )
    val step1Completion = completion("completion-1", step1Response, TokenUsage(100, 50, 150))

    // After the tool ran, the model is called again. This is where the bug occurred - the conversation now contains:
    // 1. UserMessage
    // 2. AssistantMessage with tool calls (must be properly serialized!)
    // 3. ToolMessage with result (must be properly serialized!)
    val step3Response =
      AssistantMessage(contentOpt = Some("I've added the sword to your inventory."), toolCalls = Seq.empty)
    val step3Completion = completion("completion-2", step3Response, TokenUsage(150, 75, 225))

    val step4Response   = AssistantMessage(contentOpt = Some("Your inventory contains: sword"), toolCalls = Seq.empty)
    val step4Completion = completion("completion-3", step4Response, TokenUsage(200, 100, 300))

    inSequence {
      (mockClient.complete _).expects(*, *).returning(Right(step1Completion)).once()

      // This mock verifies that the conversation is properly serialized
      (mockClient.complete _)
        .expects(where { (conv: Conversation, _: CompletionOptions) =>
          val messages = conv.messages

          // Should have: SystemMessage, UserMessage, AssistantMessage (with tool calls), ToolMessage
          messages should have size 4

          // Validate each message
          Message.validateConversation(messages.toList) should be(Symbol("right"))

          true
        })
        .returning(Right(step3Completion))
        .once()

      (mockClient.complete _).expects(*, *).returning(Right(step4Completion)).once()
    }

    val thread1 = agent
      .run("I want to take the sword", tools)
      .fold(e => fail(s"Run failed: ${e.formatted}"), identity)
    thread1.status shouldBe ThreadStatus.Completed

    // The tool message was created, for the call the model made
    val toolMessages = thread1.messages.collect { case tm: ToolMessage => tm }
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe toolCallId
    toolMessages.head.content should include("sword")

    // The final response
    val finalAssistantMessages = thread1.messages
      .collect { case am: AssistantMessage => am }
      .filterNot(_.hasToolCalls)
    finalAssistantMessages should not be empty
    finalAssistantMessages.last.content should include("inventory")

    // Add another user message and continue the conversation
    val thread2 = agent
      .continueConversation(thread1, "What's in my inventory?", tools)
      .fold(e => fail(s"Follow-up failed: ${e.formatted}"), identity)
    thread2.status shouldBe ThreadStatus.Completed
    thread2.answer shouldBe Some("Your inventory contains: sword")

    // Verify the entire conversation is valid
    Message.validateConversation(thread2.messages.toList) should be(Symbol("right"))
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
    val tools =
      createInventoryTool().fold(e => fail(s"Setup failed: ${e.formatted}"), tool => new ToolRegistry(Seq(tool)))

    // LLM requests multiple tools
    val multiToolResponse = AssistantMessage(
      contentOpt = Some("I'll add both items to your inventory."),
      toolCalls = Seq(
        ToolCall(id = "call_1", name = "add_inventory_item", arguments = ujson.Obj("item" -> "sword")),
        ToolCall(id = "call_2", name = "add_inventory_item", arguments = ujson.Obj("item" -> "shield"))
      )
    )
    val multiToolCompletion = completion("completion-multi", multiToolResponse, TokenUsage(100, 50, 150))
    val finalCompletion =
      completion(
        "completion-final",
        AssistantMessage(contentOpt = Some("Both are in."), toolCalls = Seq.empty),
        TokenUsage(1, 1, 2)
      )

    inSequence {
      (mockClient.complete _).expects(*, *).returning(Right(multiToolCompletion)).once()
      // the model sees both results, in the order of its calls, before it is asked again
      (mockClient.complete _)
        .expects(where { (conv: Conversation, _: CompletionOptions) =>
          conv.messages.collect { case tm: ToolMessage => tm.toolCallId } == Seq("call_1", "call_2")
        })
        .returning(Right(finalCompletion))
        .once()
    }

    val thread = agent
      .run("Take the sword and the shield", tools)
      .fold(e => fail(s"Multi-tool run failed: ${e.formatted}"), identity)

    // Should have 2 tool messages
    val toolMessages = thread.messages.collect { case tm: ToolMessage => tm }
    toolMessages should have size 2
    toolMessages.map(_.toolCallId) shouldBe Seq("call_1", "call_2")

    // Verify conversation is valid
    Message.validateConversation(thread.messages.toList) should be(Symbol("right"))
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
