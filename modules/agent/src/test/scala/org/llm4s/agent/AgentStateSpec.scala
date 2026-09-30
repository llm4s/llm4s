package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry

import java.nio.file.Files

/**
 * Tests for AgentState instance methods, pruning strategies, and file I/O.
 *
 * These tests complement AgentStateSerializationSpec by focusing on:
 * - Instance methods (addMessage, addMessages, log, withStatus, toApiConversation)
 * - Conversation pruning with all strategies
 * - File save/load operations
 */
class AgentStateSpec extends AnyFlatSpec with Matchers {

  private val emptyTools = new ToolRegistry(Seq.empty)

  // ==========================================================================
  // Instance Method Tests
  // ==========================================================================

  "AgentState.addMessage" should "add a single message to conversation" in {
    val state    = AgentState(Conversation(Seq.empty), emptyTools)
    val newState = state.addMessage(UserMessage("Hello"))

    newState.conversation.messages should have size 1
    newState.conversation.messages.head shouldBe a[UserMessage]
    newState.conversation.messages.head.content shouldBe "Hello"
  }

  it should "append to existing messages" in {
    val state    = AgentState(Conversation(Seq(UserMessage("First"))), emptyTools)
    val newState = state.addMessage(AssistantMessage("Second"))

    newState.conversation.messages should have size 2
    newState.conversation.messages(0).content shouldBe "First"
    newState.conversation.messages(1).content shouldBe "Second"
  }

  it should "not mutate original state" in {
    val state    = AgentState(Conversation(Seq(UserMessage("Hello"))), emptyTools)
    val newState = state.addMessage(AssistantMessage("World"))

    state.conversation.messages should have size 1
    newState.conversation.messages should have size 2
  }

  "AgentState.addMessages" should "add multiple messages at once" in {
    val state = AgentState(Conversation(Seq.empty), emptyTools)
    val newState = state.addMessages(
      Seq(
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2")
      )
    )

    newState.conversation.messages should have size 3
  }

  it should "handle empty sequence" in {
    val state    = AgentState(Conversation(Seq(UserMessage("Existing"))), emptyTools)
    val newState = state.addMessages(Seq.empty)

    newState.conversation.messages should have size 1
  }

  "AgentState.log" should "add log entry" in {
    val state    = AgentState(Conversation(Seq.empty), emptyTools)
    val newState = state.log("Step 1 completed")

    newState.logs should have size 1
    newState.logs.head shouldBe "Step 1 completed"
  }

  it should "append to existing logs" in {
    val state    = AgentState(Conversation(Seq.empty), emptyTools, logs = Seq("Log 1"))
    val newState = state.log("Log 2").log("Log 3")

    newState.logs shouldBe Seq("Log 1", "Log 2", "Log 3")
  }

  "AgentState.withStatus" should "update status" in {
    val state    = AgentState(Conversation(Seq.empty), emptyTools)
    val newState = state.withStatus(AgentStatus.Complete)

    newState.status shouldBe AgentStatus.Complete
  }

  it should "update to Failed status with error" in {
    val state    = AgentState(Conversation(Seq.empty), emptyTools)
    val newState = state.withStatus(AgentStatus.Failed("Something broke"))

    newState.status shouldBe a[AgentStatus.Failed]
    newState.status.asInstanceOf[AgentStatus.Failed].error shouldBe "Something broke"
  }

  "AgentState.toApiConversation" should "inject system message at beginning" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Hello"), AssistantMessage("Hi"))),
      tools = emptyTools,
      systemMessage = Some(SystemMessage("You are helpful"))
    )

    val apiConv = state.toApiConversation

    apiConv.messages should have size 3
    apiConv.messages.head shouldBe a[SystemMessage]
    apiConv.messages.head.content shouldBe "You are helpful"
    apiConv.messages(1).content shouldBe "Hello"
    apiConv.messages(2).content shouldBe "Hi"
  }

  it should "return conversation as-is when no system message" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Hello"))),
      tools = emptyTools,
      systemMessage = None
    )

    val apiConv = state.toApiConversation

    apiConv.messages should have size 1
    apiConv.messages.head.content shouldBe "Hello"
  }

  // ==========================================================================
  // Pruning Strategy Tests - OldestFirst
  // ==========================================================================

  "AgentState.pruneConversation with OldestFirst" should "not prune when under limit" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(maxMessages = Some(10))
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages should have size 4
  }

  it should "remove oldest messages when over limit" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2"),
          UserMessage("Q3"),
          AssistantMessage("A3")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = PruningStrategy.OldestFirst)
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages should have size 4
    pruned.conversation.messages.head.content shouldBe "Q2"
    pruned.conversation.messages.last.content shouldBe "A3"
  }

  it should "preserve system message when configured" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          SystemMessage("System prompt"),
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(
      maxMessages = Some(3),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.OldestFirst
    )
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages.head shouldBe a[SystemMessage]
    pruned.conversation.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - MiddleOut
  // ==========================================================================

  "AgentState.pruneConversation with MiddleOut" should "keep start and end messages" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2"),
          UserMessage("Q3"),
          AssistantMessage("A3")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = PruningStrategy.MiddleOut)
    val pruned = AgentState.pruneConversation(state, config)

    // Should keep first 2 and last 2
    pruned.conversation.messages should have size 4
    pruned.conversation.messages.head.content shouldBe "Q1"
    pruned.conversation.messages(1).content shouldBe "A1"
    pruned.conversation.messages(2).content shouldBe "Q3"
    pruned.conversation.messages(3).content shouldBe "A3"
  }

  it should "preserve system message when configured" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          SystemMessage("System"),
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2"),
          UserMessage("Q3"),
          AssistantMessage("A3")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(
      maxMessages = Some(4),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.MiddleOut
    )
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - RecentTurnsOnly
  // ==========================================================================

  "AgentState.pruneConversation with RecentTurnsOnly" should "keep only recent turns" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2"),
          UserMessage("Q3"),
          AssistantMessage("A3")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(
      maxMessages = Some(4),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(2)
    )
    val pruned = AgentState.pruneConversation(state, config)

    // Should keep last 2 turns (Q2+A2 and Q3+A3)
    pruned.conversation.messages should have size 4
    pruned.conversation.messages.head.content shouldBe "Q2"
    pruned.conversation.messages.last.content shouldBe "A3"
  }

  it should "keep all turns when fewer than limit" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Q1"),
          AssistantMessage("A1")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(
      maxMessages = Some(2),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
    )
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages should have size 2
  }

  it should "preserve system message when configured" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          SystemMessage("System"),
          UserMessage("Q1"),
          AssistantMessage("A1"),
          UserMessage("Q2"),
          AssistantMessage("A2")
        )
      ),
      tools = emptyTools
    )

    val config = ContextWindowConfig(
      maxMessages = Some(3),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.RecentTurnsOnly(1)
    )
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - Custom
  // ==========================================================================

  "AgentState.pruneConversation with Custom" should "apply custom function" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Keep"),
          AssistantMessage("Remove"),
          UserMessage("Keep"),
          AssistantMessage("Remove")
        )
      ),
      tools = emptyTools
    )

    // Custom strategy: keep only UserMessages
    val customFn: Seq[Message] => Seq[Message] = msgs => msgs.filter(_.isInstanceOf[UserMessage])

    val config = ContextWindowConfig(
      maxMessages = Some(2),
      pruningStrategy = PruningStrategy.Custom(customFn)
    )
    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages should have size 2
    pruned.conversation.messages.forall(_.isInstanceOf[UserMessage]) shouldBe true
  }

  // ==========================================================================
  // Token-based Pruning Tests
  // ==========================================================================

  "AgentState.pruneConversation with token limit" should "prune based on token count" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Short"),
          AssistantMessage("Short"),
          UserMessage("This is a longer message with more words"),
          AssistantMessage("Another longer response")
        )
      ),
      tools = emptyTools
    )

    // Simple token counter: 1 token per character
    val tokenCounter: Message => Int = msg => msg.content.length

    val config = ContextWindowConfig(
      maxTokens = Some(50),
      pruningStrategy = PruningStrategy.OldestFirst
    )
    val pruned = AgentState.pruneConversation(state, config, tokenCounter)

    // Should prune to fit within 50 tokens
    val totalTokens = pruned.conversation.messages.map(tokenCounter).sum
    totalTokens should be <= 50
  }

  // ==========================================================================
  // File I/O Tests
  // ==========================================================================

  "AgentState.saveToFile and loadFromFile" should "round-trip state to file" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Hello"),
          AssistantMessage("Hi there")
        )
      ),
      tools = emptyTools,
      initialQuery = Some("Hello"),
      status = AgentStatus.Complete,
      logs = Seq("Initialized", "Completed"),
      systemMessage = Some(SystemMessage("You are helpful"))
    )

    val tempFile = Files.createTempFile("agent-state-test", ".json")
    try {
      // Save
      val saveResult = AgentState.saveToFile(state, tempFile.toString)
      saveResult.isRight shouldBe true

      // Load
      val loadResult = AgentState.loadFromFile(tempFile.toString, emptyTools)
      loadResult.isRight shouldBe true

      val loaded = loadResult.toOption.get
      loaded.conversation.messages should have size 2
      loaded.initialQuery shouldBe Some("Hello")
      loaded.status shouldBe AgentStatus.Complete
      loaded.logs shouldBe Seq("Initialized", "Completed")
      loaded.systemMessage.map(_.content) shouldBe Some("You are helpful")
    } finally Files.deleteIfExists(tempFile)
  }

  it should "return error for non-existent file" in {
    val result = AgentState.loadFromFile("/nonexistent/path/file.json", emptyTools)
    result.isLeft shouldBe true
  }

  it should "return error for invalid JSON" in {
    val tempFile = Files.createTempFile("invalid-json", ".json")
    try {
      Files.write(tempFile, "not valid json".getBytes)
      val result = AgentState.loadFromFile(tempFile.toString, emptyTools)
      result.isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  // ==========================================================================
  // Edge Cases
  // ==========================================================================

  "AgentState" should "handle empty conversation" in {
    val state = AgentState(Conversation(Seq.empty), emptyTools)

    state.conversation.messages shouldBe empty
    state.toApiConversation.messages shouldBe empty
  }

  it should "chain multiple operations" in {
    val state = AgentState(Conversation(Seq.empty), emptyTools)

    val finalState = state
      .addMessage(UserMessage("Q1"))
      .log("Received query")
      .addMessage(AssistantMessage("A1"))
      .log("Generated response")
      .withStatus(AgentStatus.Complete)

    finalState.conversation.messages should have size 2
    finalState.logs should have size 2
    finalState.status shouldBe AgentStatus.Complete
  }

  it should "preserve tools reference through operations" in {
    val tools = new ToolRegistry(Seq.empty)
    val state = AgentState(Conversation(Seq.empty), tools)

    val newState = state.addMessage(UserMessage("Hello"))

    (newState.tools should be).theSameInstanceAs(tools)
  }

  it should "preserve completionOptions through operations" in {
    val options = CompletionOptions(temperature = 0.5, maxTokens = Some(1000))
    val state   = AgentState(Conversation(Seq.empty), emptyTools, completionOptions = options)

    val newState = state.addMessage(UserMessage("Hello"))

    newState.completionOptions.temperature shouldBe 0.5
    newState.completionOptions.maxTokens shouldBe Some(1000)
  }
}
