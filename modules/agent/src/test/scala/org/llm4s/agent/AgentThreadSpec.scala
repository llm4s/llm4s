package org.llm4s.agent

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/**
 * Tests for AgentThread: its accessors and setters, conversation pruning with every strategy, and file I/O.
 *
 * AgentThreadSerializationSpec covers the JSON form in detail.
 */
class AgentThreadSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Accessors and setters
  // ==========================================================================

  "AgentThread.conversation" should "be the messages as a Conversation" in {
    val thread = AgentThread("t", messages = Seq(UserMessage("Hello"), AssistantMessage("Hi")))

    thread.conversation.messages shouldBe thread.messages
    thread.conversation.messageCount shouldBe 2
  }

  "AgentThread.toApiConversation" should "inject the system message at the beginning" in {
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Hello"), AssistantMessage("Hi")),
      systemMessage = Some(SystemMessage("You are helpful"))
    )

    val api = thread.toApiConversation
    api.messages should have size 3
    api.messages.head shouldBe a[SystemMessage]
    api.messages.head.content shouldBe "You are helpful"
  }

  it should "return the conversation as-is when there is no system message" in {
    val thread = AgentThread("t", messages = Seq(UserMessage("Hello")))

    thread.toApiConversation.messages shouldBe thread.messages
  }

  "AgentThread.answer" should "be the last assistant reply of a completed thread" in {
    AgentThread(
      "t",
      messages = Seq(UserMessage("q"), AssistantMessage("first"), UserMessage("q2"), AssistantMessage("second"))
    ).answer shouldBe Some("second")
  }

  it should "be absent for a thread that did not complete, and for one with no reply" in {
    val failed = AgentThread(
      "t",
      messages = Seq(UserMessage("q"), AssistantMessage("partial")),
      status = ThreadStatus.Failed("limit")
    )
    failed.answer shouldBe None
    AgentThread("t", messages = Seq(UserMessage("q"))).answer shouldBe None
  }

  "AgentThread.isFinished" should "be true for Completed and Failed, false for Suspended" in {
    AgentThread("t").isFinished shouldBe true
    AgentThread("t", status = ThreadStatus.Failed("e")).isFinished shouldBe true
    AgentThread("t", status = ThreadStatus.Suspended(Vector.empty)).isFinished shouldBe false
  }

  "AgentThread setters" should "return a new thread and leave the original unchanged" in {
    val thread  = AgentThread("t", messages = Seq(UserMessage("Hello")))
    val changed = thread.withStatus(ThreadStatus.Failed("e")).withMessages(Seq(UserMessage("Other"))).withThreadId("u")

    thread.status shouldBe ThreadStatus.Completed
    thread.messages.map(_.content) shouldBe Seq("Hello")
    thread.threadId shouldBe "t"
    changed.status shouldBe ThreadStatus.Failed("e")
    changed.messages.map(_.content) shouldBe Seq("Other")
    changed.threadId shouldBe "u"
  }

  it should "keep the system message, options and usage through other changes" in {
    val options = CompletionOptions(temperature = 0.2, maxTokens = Some(50))
    val usage   = UsageSummary().add("m", TokenUsage(1, 2, 3), Some(0.5))
    val thread = AgentThread(
      "t",
      systemMessage = Some(SystemMessage("sys")),
      completionOptions = options,
      usage = usage
    ).withMessages(Seq(UserMessage("x"))).withStatus(ThreadStatus.Failed("e"))

    thread.systemMessage.map(_.content) shouldBe Some("sys")
    thread.completionOptions.temperature shouldBe 0.2
    thread.completionOptions.maxTokens shouldBe Some(50)
    thread.usage shouldBe usage
  }

  "AgentThread.toTraceEvent" should "carry the status, the messages and no log" in {
    val thread = AgentThread("t", messages = Seq(UserMessage("q"), AssistantMessage("a")))
    val event  = thread.toTraceEvent

    event.status shouldBe "Completed"
    event.messageCount shouldBe 2
    event.logCount shouldBe 0
    event.messages.map(_.content) shouldBe Seq("q", "a")
  }

  // ==========================================================================
  // Pruning Strategy Tests - OldestFirst
  // ==========================================================================

  "AgentThread.pruned with OldestFirst" should "not prune when under limit" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2")
      )
    )

    val config = ContextWindowConfig(maxMessages = Some(10))
    val pruned = state.pruned(config)

    pruned.messages should have size 4
  }

  it should "remove oldest messages when over limit" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2"),
        UserMessage("Q3"),
        AssistantMessage("A3")
      )
    )

    val config = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = PruningStrategy.OldestFirst)
    val pruned = state.pruned(config)

    pruned.messages should have size 4
    pruned.messages.head.content shouldBe "Q2"
    pruned.messages.last.content shouldBe "A3"
  }

  it should "preserve system message when configured" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        SystemMessage("System prompt"),
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2")
      )
    )

    val config = ContextWindowConfig(
      maxMessages = Some(3),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.OldestFirst
    )
    val pruned = state.pruned(config)

    pruned.messages.head shouldBe a[SystemMessage]
    pruned.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - MiddleOut
  // ==========================================================================

  "AgentThread.pruned with MiddleOut" should "keep start and end messages" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2"),
        UserMessage("Q3"),
        AssistantMessage("A3")
      )
    )

    val config = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = PruningStrategy.MiddleOut)
    val pruned = state.pruned(config)

    // Should keep first 2 and last 2
    pruned.messages should have size 4
    pruned.messages.head.content shouldBe "Q1"
    pruned.messages(1).content shouldBe "A1"
    pruned.messages(2).content shouldBe "Q3"
    pruned.messages(3).content shouldBe "A3"
  }

  it should "preserve system message when configured" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        SystemMessage("System"),
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2"),
        UserMessage("Q3"),
        AssistantMessage("A3")
      )
    )

    val config = ContextWindowConfig(
      maxMessages = Some(4),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.MiddleOut
    )
    val pruned = state.pruned(config)

    pruned.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - RecentTurnsOnly
  // ==========================================================================

  "AgentThread.pruned with RecentTurnsOnly" should "keep only recent turns" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2"),
        UserMessage("Q3"),
        AssistantMessage("A3")
      )
    )

    val config = ContextWindowConfig(
      maxMessages = Some(4),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(2)
    )
    val pruned = state.pruned(config)

    // Should keep last 2 turns (Q2+A2 and Q3+A3)
    pruned.messages should have size 4
    pruned.messages.head.content shouldBe "Q2"
    pruned.messages.last.content shouldBe "A3"
  }

  it should "keep all turns when fewer than limit" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Q1"),
        AssistantMessage("A1")
      )
    )

    val config = ContextWindowConfig(
      maxMessages = Some(2),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
    )
    val pruned = state.pruned(config)

    pruned.messages should have size 2
  }

  it should "preserve system message when configured" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        SystemMessage("System"),
        UserMessage("Q1"),
        AssistantMessage("A1"),
        UserMessage("Q2"),
        AssistantMessage("A2")
      )
    )

    val config = ContextWindowConfig(
      maxMessages = Some(3),
      preserveSystemMessage = true,
      pruningStrategy = PruningStrategy.RecentTurnsOnly(1)
    )
    val pruned = state.pruned(config)

    pruned.messages.exists(_.isInstanceOf[SystemMessage]) shouldBe true
  }

  // ==========================================================================
  // Pruning Strategy Tests - Custom
  // ==========================================================================

  "AgentThread.pruned with Custom" should "apply custom function" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Keep"),
        AssistantMessage("Remove"),
        UserMessage("Keep"),
        AssistantMessage("Remove")
      )
    )

    // Custom strategy: keep only UserMessages
    val customFn: Seq[Message] => Seq[Message] = msgs => msgs.filter(_.isInstanceOf[UserMessage])

    val config = ContextWindowConfig(
      maxMessages = Some(2),
      pruningStrategy = PruningStrategy.Custom(customFn)
    )
    val pruned = state.pruned(config)

    pruned.messages should have size 2
    pruned.messages.forall(_.isInstanceOf[UserMessage]) shouldBe true
  }

  // ==========================================================================
  // Token-based Pruning Tests
  // ==========================================================================

  "AgentThread.pruned with token limit" should "prune based on token count" in {
    val state = AgentThread(
      threadId = "t",
      messages = Seq(
        UserMessage("Short"),
        AssistantMessage("Short"),
        UserMessage("This is a longer message with more words"),
        AssistantMessage("Another longer response")
      )
    )

    // Simple token counter: 1 token per character
    val tokenCounter: Message => Int = msg => msg.content.length

    val config = ContextWindowConfig(
      maxTokens = Some(50),
      pruningStrategy = PruningStrategy.OldestFirst
    )
    val pruned = state.pruned(config, tokenCounter)

    // Should prune to fit within 50 tokens
    val totalTokens = pruned.messages.map(tokenCounter).sum
    totalTokens should be <= 50
  }

  // ==========================================================================
  // File I/O Tests
  // ==========================================================================

  "AgentThread.saveToFile and loadFromFile" should "round-trip a thread to file" in {
    val thread = AgentThread(
      threadId = "thread-7",
      messages = Seq(UserMessage("Hello"), AssistantMessage("Hi there")),
      systemMessage = Some(SystemMessage("You are helpful")),
      status = ThreadStatus.Completed
    )

    val tempFile = Files.createTempFile("agent-thread-test", ".json")
    try {
      AgentThread.saveToFile(thread, tempFile.toString).isRight shouldBe true

      val loaded = AgentThread.loadFromFile(tempFile.toString).fold(e => fail(e.message), identity)
      loaded.threadId shouldBe "thread-7"
      loaded.messages should have size 2
      loaded.status shouldBe ThreadStatus.Completed
      loaded.systemMessage.map(_.content) shouldBe Some("You are helpful")
    } finally Files.deleteIfExists(tempFile)
  }

  it should "return error for non-existent file" in {
    AgentThread.loadFromFile("/nonexistent/path/file.json").isLeft shouldBe true
  }

  it should "return error for invalid JSON" in {
    val tempFile = Files.createTempFile("invalid-json", ".json")
    try {
      Files.write(tempFile, "not valid json".getBytes)
      AgentThread.loadFromFile(tempFile.toString).isLeft shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  // ==========================================================================
  // Edge Cases
  // ==========================================================================

  "AgentThread" should "handle an empty conversation" in {
    val thread = AgentThread("t")

    thread.messages shouldBe empty
    thread.toApiConversation.messages shouldBe empty
    thread.answer shouldBe None
  }

  it should "be pruned without change when it holds no messages" in {
    AgentThread("t").pruned(ContextWindowConfig(maxMessages = Some(1))).messages shouldBe empty
  }
}
