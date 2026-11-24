package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Test suite for Conversation convenience methods.
 * Validates the new helper methods for creating and manipulating conversations.
 */
class ConversationConvenienceMethodsSpec extends AnyFlatSpec with Matchers {

  // --- Conversation.fromPrompts tests ---

  "Conversation.fromPrompts" should "create a valid conversation with system and user messages" in {
    val result = Conversation.fromPrompts("You are a helpful assistant", "What is 2+2?")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(2)
      conv.messages(0) should be(SystemMessage("You are a helpful assistant"))
      conv.messages(1) should be(UserMessage("What is 2+2?"))
    }
  }

  it should "validate message content is not empty" in {
    val result = Conversation.fromPrompts("", "What is 2+2?")

    result.isLeft shouldBe true
  }

  it should "fail when user prompt is empty" in {
    val result = Conversation.fromPrompts("You are a helpful assistant", "")

    result.isLeft shouldBe true
  }

  it should "fail when both prompts are empty" in {
    val result = Conversation.fromPrompts("", "")

    result.isLeft shouldBe true
  }

  it should "trim whitespace and fail on whitespace-only prompts" in {
    val result = Conversation.fromPrompts("   ", "   ")

    result.isLeft shouldBe true
  }

  it should "preserve leading/trailing whitespace in valid prompts" in {
    val result = Conversation.fromPrompts("  System prompt  ", "  User prompt  ")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(2)
      conv.messages(0).content should be("  System prompt  ")
      conv.messages(1).content should be("  User prompt  ")
    }
  }

  // --- Conversation.userOnly tests ---

  "Conversation.userOnly" should "create a valid conversation with single user message" in {
    val result = Conversation.userOnly("What is the capital of France?")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(1)
      conv.messages(0) should be(UserMessage("What is the capital of France?"))
    }
  }

  it should "fail when prompt is empty" in {
    val result = Conversation.userOnly("")

    result.isLeft shouldBe true
  }

  it should "fail when prompt is whitespace-only" in {
    val result = Conversation.userOnly("   ")

    result.isLeft shouldBe true
  }

  it should "preserve leading/trailing whitespace in valid prompts" in {
    val result = Conversation.userOnly("  User prompt  ")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(1)
      conv.messages(0).content should be("  User prompt  ")
    }
  }

  // --- Conversation.systemOnly tests ---

  "Conversation.systemOnly" should "create a valid conversation with single system message" in {
    val result = Conversation.systemOnly("You are a helpful assistant")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(1)
      conv.messages(0) should be(SystemMessage("You are a helpful assistant"))
    }
  }

  it should "fail when prompt is empty" in {
    val result = Conversation.systemOnly("")

    result.isLeft shouldBe true
  }

  it should "fail when prompt is whitespace-only" in {
    val result = Conversation.systemOnly("   ")

    result.isLeft shouldBe true
  }

  it should "preserve leading/trailing whitespace in valid prompts" in {
    val result = Conversation.systemOnly("  System prompt  ")

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(1)
      conv.messages(0).content should be("  System prompt  ")
    }
  }

  // --- Conversation.lastMessage tests ---

  "Conversation.lastMessage" should "return the last message in a conversation" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System prompt"),
        UserMessage("User message"),
        AssistantMessage(Some("Assistant response"))
      )
    )

    conv.lastMessage should be(Some(AssistantMessage(Some("Assistant response"))))
  }

  it should "return None for an empty conversation" in {
    val conv = Conversation.empty

    conv.lastMessage should be(None)
  }

  it should "return the only message in a single-message conversation" in {
    val conv = Conversation(Seq(UserMessage("Hello")))

    conv.lastMessage should be(Some(UserMessage("Hello")))
  }

  // --- Conversation.messageCount tests ---

  "Conversation.messageCount" should "return correct count for multi-message conversation" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System"),
        UserMessage("User"),
        AssistantMessage(Some("Assistant"))
      )
    )

    conv.messageCount should be(3)
  }

  it should "return 0 for empty conversation" in {
    val conv = Conversation.empty

    conv.messageCount should be(0)
  }

  it should "return 1 for single-message conversation" in {
    val conv = Conversation(Seq(UserMessage("Hello")))

    conv.messageCount should be(1)
  }

  // --- Conversation.filterByRole tests ---

  "Conversation.filterByRole" should "filter user messages" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System"),
        UserMessage("User 1"),
        AssistantMessage(Some("Assistant")),
        UserMessage("User 2")
      )
    )

    val userMessages = conv.filterByRole(MessageRole.User)

    (userMessages should have).length(2)
    userMessages(0) should be(UserMessage("User 1"))
    userMessages(1) should be(UserMessage("User 2"))
  }

  it should "filter system messages" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System 1"),
        UserMessage("User"),
        SystemMessage("System 2"),
        AssistantMessage(Some("Assistant"))
      )
    )

    val systemMessages = conv.filterByRole(MessageRole.System)

    (systemMessages should have).length(2)
    systemMessages(0) should be(SystemMessage("System 1"))
    systemMessages(1) should be(SystemMessage("System 2"))
  }

  it should "filter assistant messages" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System"),
        UserMessage("User"),
        AssistantMessage(Some("Assistant 1")),
        UserMessage("User 2"),
        AssistantMessage(Some("Assistant 2"))
      )
    )

    val assistantMessages = conv.filterByRole(MessageRole.Assistant)

    (assistantMessages should have).length(2)
    assistantMessages(0) should be(AssistantMessage(Some("Assistant 1")))
    assistantMessages(1) should be(AssistantMessage(Some("Assistant 2")))
  }

  it should "return empty sequence when no messages match role" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System"),
        UserMessage("User"),
        AssistantMessage(Some("Assistant"))
      )
    )

    val toolMessages = conv.filterByRole(MessageRole.Tool)

    toolMessages should be(empty)
  }

  it should "return empty sequence for empty conversation" in {
    val conv = Conversation.empty

    val userMessages = conv.filterByRole(MessageRole.User)

    userMessages should be(empty)
  }

  // --- Integration tests ---

  "Convenience methods" should "work together for common workflow" in {
    // Create conversation with fromPrompts
    val result = Conversation.fromPrompts(
      "You are a math tutor",
      "What is 2+2?"
    )

    result.isRight shouldBe true
    result.foreach { conv =>
      // Check message count
      conv.messageCount should be(2)

      // Add assistant response
      val updatedConv = conv.addMessage(AssistantMessage(Some("The answer is 4")))
      updatedConv.messageCount should be(3)

      // Get last message
      updatedConv.lastMessage should be(Some(AssistantMessage(Some("The answer is 4"))))

      // Filter by role
      val userMessages = updatedConv.filterByRole(MessageRole.User)
      (userMessages should have).length(1)
      userMessages(0) should be(UserMessage("What is 2+2?"))
    }
  }

  it should "chain operations functionally" in {
    val result = for {
      conv <- Conversation.userOnly("Hello, world!")
    } yield conv
      .addMessage(AssistantMessage(Some("Hello! How can I help?")))
      .addMessage(UserMessage("Tell me a joke"))

    result.isRight shouldBe true
    result.foreach { conv =>
      conv.messageCount should be(3)
      (conv.filterByRole(MessageRole.User) should have).length(2)
      (conv.filterByRole(MessageRole.Assistant) should have).length(1)
    }
  }
}
