package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * The complete tool calling flow - query, tool call, tool execution, answer, follow-up - with
 * every request the model is sent a valid conversation. The provider-specific variants this spec
 * once had ran identical code over mocks; one scripted client covers them.
 */
class AgentToolCallingMultiProviderTest extends AnyFlatSpec with Matchers {

  case class ToolResult(success: Boolean, message: String, item: String)
  implicit val toolResultRW: ReadWriter[ToolResult] = macroRW[ToolResult]

  def createInventoryTool(): Result[ToolFunction[Map[String, Any], ToolResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Add item to inventory")
      .withProperty(Schema.property("item", Schema.string("Item name")))

    ToolBuilder[Map[String, Any], ToolResult]("add_inventory_item", "Add an item to the player's inventory", schema)
      .withHandler { params =>
        val item = params.getString("item").getOrElse("unknown")
        Right(ToolResult(success = true, message = s"Added '$item' to inventory", item = item))
      }
      .buildSafe()
  }

  private def agentWith(client: ScriptedLLMClient): Agent = {
    val tool = createInventoryTool().fold(e => fail(s"Setup failed: ${e.formatted}"), identity)
    built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(tool))))
  }

  private def add(id: String, item: String) = ToolCall(id, "add_inventory_item", ujson.Obj("item" -> item))

  "Agent" should "handle the complete tool calling flow and a follow-up turn" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withMessage(
        AssistantMessage(Some("I'll add the sword to your inventory."), Seq(add("call_test_123", "sword")))
      ),
      CompletionFixture.simple("I've added the sword to your inventory."),
      CompletionFixture.simple("Your inventory contains: sword")
    )
    val agent = agentWith(client)

    val first        = agent.run("I want to take the sword").value
    val toolMessages = first.messages.collect { case tm: ToolMessage => tm }
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call_test_123"
    toolMessages.head.content should include("sword")
    first.answer.get should include("inventory")

    // the request after the tool ran: user, assistant with its call, the call's result - and valid
    client.sent(1) should have size 3
    Message.validateConversation(client.sent(1).toList) shouldBe Right(())

    val followUp = agent.continueConversation(first, "What's in my inventory?").value
    followUp.answer shouldBe Some("Your inventory contains: sword")
    Message.validateConversation(followUp.messages.toList) shouldBe Right(())
  }

  it should "handle multiple tool calls in one response" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withMessage(
        AssistantMessage(
          Some("I'll add both items to your inventory."),
          Seq(add("call_1", "sword"), add("call_2", "shield"))
        )
      ),
      CompletionFixture.simple("Both added.")
    )

    val result = agentWith(client).run("Take the sword and the shield").value

    val toolMessages = result.messages.collect { case tm: ToolMessage => tm }
    toolMessages.map(_.toolCallId) shouldBe Vector("call_1", "call_2")
    toolMessages(0).content should include("sword")
    toolMessages(1).content should include("shield")
    Message.validateConversation(client.sent(1).toList) shouldBe Right(())
    result.answer shouldBe Some("Both added.")
  }
}
