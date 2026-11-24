package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SemanticBlocksSpec extends AnyFlatSpec with Matchers {

  // ============ Basic Grouping ============

  "SemanticBlocks.groupIntoSemanticBlocks" should "pair user and assistant messages into blocks" in {
    val messages = Seq(
      UserMessage("Question 1"),
      AssistantMessage("Answer 1"),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    blocks.length shouldBe 2

    blocks(0).blockType shouldBe SemanticBlockType.UserAssistantPair
    blocks(0).messages.length shouldBe 2

    blocks(1).blockType shouldBe SemanticBlockType.UserAssistantPair
    blocks(1).messages.length shouldBe 2
  }

  it should "handle standalone assistant messages" in {
    val messages = Seq(
      AssistantMessage("Greeting without user input")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    blocks.length shouldBe 1
    blocks(0).blockType shouldBe SemanticBlockType.StandaloneAssistant
  }

  it should "include tool messages in the current block" in {
    val messages = Seq(
      UserMessage("Get the weather"),
      AssistantMessage(
        content = "Let me check.",
        toolCalls = Seq(ToolCall(ToolCallId("call_1"), "weather", ujson.Obj()))
      ),
      ToolMessage("""{"temp": 20}""", ToolCallId("call_1")),
      AssistantMessage("It's 20 degrees.")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    // The user question should start a block, assistant + tool + final assistant follow
    blocks.length should be >= 1
    // Tool messages should be included in blocks
    blocks.flatMap(_.messages).exists(_.isInstanceOf[ToolMessage]) shouldBe true
  }

  it should "create system message blocks" in {
    val messages = Seq(
      SystemMessage("You are a helpful assistant."),
      UserMessage("Hello"),
      AssistantMessage("Hi there!")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    // System message should be in its own block or treated specially
    blocks.length should be >= 2
  }

  // ============ Edge Cases ============

  it should "handle empty message list" in {
    val messages = Seq.empty[Message]

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    blocks shouldBe empty
  }

  it should "handle consecutive user messages" in {
    val messages = Seq(
      UserMessage("First question"),
      UserMessage("Second question, before answer"),
      AssistantMessage("Here's the answer to both")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    // Each user message should start a new block
    blocks.length shouldBe 2
  }

  it should "handle consecutive assistant messages" in {
    val messages = Seq(
      UserMessage("Question"),
      AssistantMessage("First part of answer"),
      AssistantMessage("Second part of answer")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    // First assistant completes the pair, second is standalone
    blocks.length shouldBe 2
  }

  it should "handle tool message without preceding user message" in {
    val messages = Seq(
      ToolMessage("""{"result": "data"}""", ToolCallId("orphan_call"))
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get
    blocks.length shouldBe 1
    blocks(0).blockType shouldBe SemanticBlockType.StandaloneTool
  }

  // ============ SemanticBlock Operations ============

  "SemanticBlock" should "add assistant message and clear expecting flag" in {
    val block = SemanticBlock.startUserBlock(UserMessage("Question"))
    block.expectingAssistantResponse shouldBe true

    val completed = block.addAssistantMessage(AssistantMessage("Answer"))
    completed.expectingAssistantResponse shouldBe false
    completed.messages.length shouldBe 2
  }

  it should "add tool message without changing expecting flag" in {
    val block = SemanticBlock
      .startUserBlock(UserMessage("Question"))
      .addAssistantMessage(
        AssistantMessage(
          content = "Checking...",
          toolCalls = Seq(ToolCall(ToolCallId("call_1"), "tool", ujson.Obj()))
        )
      )

    val withTool = block.addToolMessage(ToolMessage("result", ToolCallId("call_1")))
    withTool.messages.length shouldBe 3
  }

  it should "provide meaningful block summary" in {
    val userBlock = SemanticBlock
      .startUserBlock(UserMessage("What is the weather today?"))
      .addAssistantMessage(AssistantMessage("It's sunny."))

    val summary = userBlock.getBlockSummary
    summary should include("Q&A pair")
    summary should include("What is the weather")
  }

  // ============ Block Factory Methods ============

  "SemanticBlock.startUserBlock" should "create block expecting response" in {
    val block = SemanticBlock.startUserBlock(UserMessage("Hello"))

    block.blockType shouldBe SemanticBlockType.UserAssistantPair
    block.expectingAssistantResponse shouldBe true
    block.messages.length shouldBe 1
  }

  "SemanticBlock.standaloneAssistant" should "create non-expecting block" in {
    val block = SemanticBlock.standaloneAssistant(AssistantMessage("Greeting"))

    block.blockType shouldBe SemanticBlockType.StandaloneAssistant
    block.expectingAssistantResponse shouldBe false
  }

  "SemanticBlock.standaloneTool" should "create tool block" in {
    val block = SemanticBlock.standaloneTool(ToolMessage("data", ToolCallId("call_1")))

    block.blockType shouldBe SemanticBlockType.StandaloneTool
    block.messages.length shouldBe 1
  }

  // ============ Complex Scenarios ============

  "SemanticBlocks" should "handle realistic multi-turn conversation" in {
    val messages = Seq(
      SystemMessage("You are a coding assistant."),
      UserMessage("How do I write a function in Scala?"),
      AssistantMessage("Here's how to write a function:\n```scala\ndef add(a: Int, b: Int): Int = a + b\n```"),
      UserMessage("Can you explain the syntax?"),
      AssistantMessage("Let me break it down for you..."),
      UserMessage("What about pattern matching?"),
      AssistantMessage(
        content = "Let me find an example.",
        toolCalls = Seq(ToolCall(ToolCallId("call_1"), "search_docs", ujson.Obj("query" -> "pattern matching")))
      ),
      ToolMessage("""{"result": "Pattern matching uses match/case"}""", ToolCallId("call_1")),
      AssistantMessage("Pattern matching in Scala works like this...")
    )

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)

    result.isRight shouldBe true
    val blocks = result.toOption.get

    // Should have: system block + 3 Q&A pairs (with last one including tool)
    blocks.length should be >= 3

    // Total messages should be preserved
    blocks.flatMap(_.messages).length shouldBe messages.length
  }

  it should "preserve message order within blocks" in {
    val messages = ContextTestFixtures.conversationWithTools.messages

    val result = SemanticBlocks.groupIntoSemanticBlocks(messages)
    result.isRight shouldBe true

    val allMessagesFromBlocks = result.toOption.get.flatMap(_.messages)

    // Messages should appear in same order
    allMessagesFromBlocks.zip(messages).foreach { case (fromBlock, original) =>
      fromBlock.content shouldBe original.content
    }
  }
}
