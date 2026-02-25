package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.MessageCreateParams
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for the two internal protocol-adaptation methods in AnthropicClient:
 *
 *  - `addMessagesToParams` — converts llm4s message types to the Anthropic API wire
 *    format; handles default system prompt injection, ToolMessage prefixing, and
 *    the rule that AssistantMessages carrying pending tool calls must be skipped.
 *
 *  - `clampBudgetTokens` — enforces the Anthropic API constraint that the
 *    extended-thinking budget must satisfy `1024 ≤ budget < maxTokens`.
 *
 * Both methods are `private[provider]`, so tests live in the same package.
 * No real network calls are made; we only test pure request-construction logic.
 */
class AnthropicClientMessageBuildingTest extends AnyFlatSpec with Matchers {

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-3-5-sonnet-latest",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private val client = new AnthropicClient(testConfig)

  /**
   * Builds a real MessageCreateParams from the given messages and serialises it
   * to a JSON string for inspection.  Uses the same Jackson mapper the
   * production code uses, so the format matches what would be sent to the API.
   */
  private def buildParamsJson(messages: Message*): String = {
    val conversation = Conversation(messages)
    val builder = MessageCreateParams
      .builder()
      .model("claude-3-5-sonnet-latest")
      .maxTokens(1024)
    client.addMessagesToParams(conversation, builder)
    val params = builder.build()
    ObjectMappers.jsonMapper().writeValueAsString(params._body())
  }

  // ---------------------------------------------------------------------------
  // addMessagesToParams — system prompt handling
  // ---------------------------------------------------------------------------

  "addMessagesToParams" should
    "inject the default system prompt when the conversation has no SystemMessage" in {
      val json = buildParamsJson(UserMessage("Hello"))
      json should include("You are Claude, a helpful AI assistant.")
    }

  it should "use an explicit SystemMessage instead of the default" in {
    val json = buildParamsJson(
      SystemMessage("You are a pirate."),
      UserMessage("Ahoy!")
    )
    json should include("You are a pirate.")
    (json should not).include("You are Claude, a helpful AI assistant.")
  }

  it should "not duplicate the system prompt when a SystemMessage is present" in {
    val json = buildParamsJson(
      SystemMessage("Custom prompt."),
      UserMessage("Hello")
    )
    // The default must not appear alongside the custom one
    (json should not).include("You are Claude, a helpful AI assistant.")
    // Only one occurrence of the custom prompt
    json.split("Custom prompt\\.").length - 1 shouldBe 1
  }

  // ---------------------------------------------------------------------------
  // addMessagesToParams — message type conversions
  // ---------------------------------------------------------------------------

  it should "include a UserMessage in the messages list" in {
    val json = buildParamsJson(UserMessage("What is 2+2?"))
    json should include("What is 2+2?")
  }

  it should "include an AssistantMessage without tool calls as an assistant turn" in {
    val json = buildParamsJson(
      UserMessage("Hello"),
      AssistantMessage(Some("Hi there!"), Seq.empty)
    )
    json should include("Hi there!")
    json should include("assistant")
  }

  it should "skip an AssistantMessage that carries pending tool calls" in {
    val toolCall = ToolCall("call-1", "get_weather", ujson.Obj("city" -> "London"))
    // Use a distinctive marker to verify it does NOT appear in the output
    val json = buildParamsJson(
      UserMessage("What is the weather?"),
      AssistantMessage(Some("TOOL_CALL_MARKER"), Seq(toolCall))
    )
    (json should not).include("TOOL_CALL_MARKER")
  }

  it should "convert a ToolMessage to a user turn with the [Tool result for …] prefix" in {
    val json = buildParamsJson(
      UserMessage("What's the weather?"),
      ToolMessage("Sunny and 22C", "call-99")
    )
    json should include("[Tool result for call-99]: Sunny and 22C")
  }

  it should "preserve the exact toolCallId in the ToolMessage prefix" in {
    val id = "abc-def-123-xyz"
    val json = buildParamsJson(
      UserMessage("Lookup something"),
      ToolMessage("result value", id)
    )
    json should include(s"[Tool result for $id]: result value")
  }

  it should "handle multiple ToolMessages in one turn, each with its own prefix" in {
    val json = buildParamsJson(
      UserMessage("Check both cities"),
      ToolMessage("Rainy", "call-a"),
      ToolMessage("Sunny", "call-b")
    )
    json should include("[Tool result for call-a]: Rainy")
    json should include("[Tool result for call-b]: Sunny")
  }

  // ---------------------------------------------------------------------------
  // addMessagesToParams — full tool-call round-trip
  // ---------------------------------------------------------------------------

  it should "handle a complete tool-call round-trip correctly" in {
    val toolCall = ToolCall("call-1", "get_weather", ujson.Obj("city" -> "London"))
    val json = buildParamsJson(
      SystemMessage("You are a weather assistant."),
      UserMessage("What's the weather in London?"),
      AssistantMessage(None, Seq(toolCall)),                   // must be skipped
      ToolMessage("15C and cloudy", "call-1"),                 // forwarded as user turn
      AssistantMessage(Some("The weather is 15C."), Seq.empty) // forwarded as assistant turn
    )
    // Custom system message used, default not injected
    json should include("You are a weather assistant.")
    (json should not).include("You are Claude, a helpful AI assistant.")
    // Tool result forwarded with prefix
    json should include("[Tool result for call-1]: 15C and cloudy")
    // Final text-only assistant message forwarded
    json should include("The weather is 15C.")
  }

  it should "handle a multi-turn conversation with no tool calls" in {
    val json = buildParamsJson(
      UserMessage("Hello"),
      AssistantMessage(Some("Hi!"), Seq.empty),
      UserMessage("How are you?"),
      AssistantMessage(Some("I am fine."), Seq.empty)
    )
    json should include("Hello")
    json should include("Hi!")
    json should include("How are you?")
    json should include("I am fine.")
  }

  // ---------------------------------------------------------------------------
  // clampBudgetTokens — boundary conditions
  // ---------------------------------------------------------------------------

  "clampBudgetTokens" should "clamp a budget below the minimum (1024) up to 1024" in {
    client.clampBudgetTokens(500, 2048) shouldBe 1024
  }

  it should "clamp a zero budget up to 1024" in {
    client.clampBudgetTokens(0, 2048) shouldBe 1024
  }

  it should "clamp a budget that exceeds maxTokens - 1 down to maxTokens - 1" in {
    client.clampBudgetTokens(3000, 2048) shouldBe 2047
  }

  it should "clamp a budget equal to maxTokens down to maxTokens - 1" in {
    client.clampBudgetTokens(2048, 2048) shouldBe 2047
  }

  it should "pass through a budget that is within the valid range" in {
    client.clampBudgetTokens(2000, 2048) shouldBe 2000
  }

  it should "accept a budget exactly at the lower bound (1024)" in {
    client.clampBudgetTokens(1024, 2048) shouldBe 1024
  }

  it should "accept a budget exactly at the upper bound (maxTokens - 1)" in {
    client.clampBudgetTokens(2047, 2048) shouldBe 2047
  }

  it should "work correctly with a large maxTokens value" in {
    client.clampBudgetTokens(50000, 200000) shouldBe 50000
    client.clampBudgetTokens(200000, 200000) shouldBe 199999
    client.clampBudgetTokens(500, 200000) shouldBe 1024
  }
}
