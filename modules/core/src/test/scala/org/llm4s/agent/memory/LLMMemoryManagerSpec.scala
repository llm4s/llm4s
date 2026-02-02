package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tests for LLMMemoryManager.
 *
 * These tests verify LLM-powered memory consolidation behavior.
 */
class LLMMemoryManagerSpec extends AnyFlatSpec with Matchers {

  // ============================================================
  // Mock LLM Client for testing
  // ============================================================

  /**
   * Mock LLM client that returns simple consolidated summaries.
   */
  class MockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      // Extract the prompt to determine what kind of consolidation
      val prompt = conversation.messages.collectFirst { case UserMessage(content) => content }.getOrElse("")

      val response = if (prompt.contains("conversation")) {
        "Consolidated conversation summary: User and assistant discussed various topics."
      } else if (prompt.contains("entity")) {
        "Consolidated entity description: An entity with multiple important characteristics."
      } else if (prompt.contains("user")) {
        "Consolidated user profile: A user with specific preferences and background."
      } else if (prompt.contains("knowledge")) {
        "Consolidated knowledge entry: Combined information from multiple sources."
      } else if (prompt.contains("task")) {
        "Consolidated task summary: Multiple tasks completed with various outcomes."
      } else {
        "Consolidated memory content."
      }

      Right(
        Completion(
          id = "mock-completion",
          created = System.currentTimeMillis(),
          content = response,
          model = "mock-model",
          message = AssistantMessage(response),
          usage = None
        )
      )
    }

    // Implement other required methods
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    def getContextWindow(): Int = 4096

    def getReserveCompletion(): Int = 1024
  }

  // ============================================================
  // Helper methods
  // ============================================================

  def createManager(): LLMMemoryManager = {
    val client = new MockLLMClient()
    LLMMemoryManager.forTesting(client)
  }

  // ============================================================
  // Tests
  // ============================================================

  "LLMMemoryManager" should "create with default configuration" in {
    val client  = new MockLLMClient()
    val store   = InMemoryStore.empty
    val manager = LLMMemoryManager.withDefaults(store, client)

    manager.config shouldBe MemoryManagerConfig.default
    manager.store shouldBe store
  }

  it should "create for testing" in {
    val client  = new MockLLMClient()
    val manager = LLMMemoryManager.forTesting(client)

    manager.config shouldBe MemoryManagerConfig.testing
  }

  it should "record messages like SimpleMemoryManager" in {
    val manager = createManager()

    val result = manager.recordMessage(
      UserMessage("Hello"),
      conversationId = "conv-1",
      importance = Some(0.8)
    )

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.All, 100)
    (memories.toOption.get should have).length(1)
  }

  it should "record conversations" in {
    val manager = createManager()

    val messages = Seq(
      UserMessage("Question 1"),
      AssistantMessage("Answer 1"),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2")
    )

    val result = manager.recordConversation(messages, "conv-1")

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.conversations, 100)
    (memories.toOption.get should have).length(4)
  }

  it should "record entity facts" in {
    val manager  = createManager()
    val entityId = EntityId.fromName("Scala")

    val result = manager.recordEntityFact(
      entityId,
      "Scala",
      "A programming language",
      "technology",
      Some(0.9)
    )

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.entities, 100)
    (memories.toOption.get should have).length(1)
  }

  it should "record user facts" in {
    val manager = createManager()

    val result = manager.recordUserFact(
      "Prefers functional programming",
      Some("user-1"),
      Some(0.8)
    )

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.userFacts, 100)
    (memories.toOption.get should have).length(1)
  }

  it should "record knowledge" in {
    val manager = createManager()

    val result = manager.recordKnowledge(
      "Scala combines OOP and FP",
      "docs/scala.md",
      Map("chapter" -> "1")
    )

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.knowledge, 100)
    (memories.toOption.get should have).length(1)
  }

  it should "record tasks" in {
    val manager = createManager()

    val result = manager.recordTask(
      "Build feature X",
      "Successfully completed",
      success = true,
      Some(0.7)
    )

    result.isRight shouldBe true

    val memories = result.toOption.get.store.recall(MemoryFilter.tasks, 100)
    (memories.toOption.get should have).length(1)
  }

  it should "not consolidate if below minimum count" in {
    val manager = createManager()

    // Add only 2 memories (below minCount of 3)
    val populated = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), None)
    } yield m2

    val consolidated = populated.flatMap(
      _.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS), // Include all
        minCount = 3
      )
    )

    consolidated.isRight shouldBe true

    // Verify no consolidation happened
    val finalStore = consolidated.toOption.get.store
    val remaining  = finalStore.recall(MemoryFilter.All, 100)

    (remaining.toOption.get should have).length(2)
  }

  it should "consolidate conversation memories when conditions are met" in {
    val manager = createManager()

    // Add 4 conversation messages
    val messages = Seq(
      UserMessage("What is Scala?"),
      AssistantMessage("Scala is a language..."),
      UserMessage("Tell me more"),
      AssistantMessage("It runs on JVM...")
    )

    val result = for {
      populated   <- manager.recordConversation(messages, "conv-1")
      statsBefore <- populated.stats
      _ = statsBefore.totalMemories shouldBe 4

      // Consolidate all memories (set olderThan to future)
      consolidated <- populated.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      statsAfter <- consolidated.stats
    } yield (statsAfter, consolidated)

    result.isRight shouldBe true
    val (statsAfter, consolidated) = result.toOption.get

    // Should have fewer memories after consolidation
    statsAfter.totalMemories should be < 4L

    // Verify consolidated memory exists
    val memories = consolidated.store.recall(MemoryFilter.conversations, 100)
    memories.isRight shouldBe true
    memories.toOption.get should not be empty

    // Consolidated memory should contain "Consolidated" in content
    val consolidatedMemory = memories.toOption.get.head
    consolidatedMemory.content should include("Consolidated")
  }

  it should "preserve importance scores during consolidation" in {
    val manager = createManager()

    val result = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), Some(0.5))
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), Some(0.9))
      m3 <- m2.recordUserFact("Fact 3", Some("user-1"), Some(0.7))

      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.userFacts, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    // Should have 1 consolidated memory
    (memories should have).length(1)

    // Should preserve max importance (0.9)
    memories.head.importance.getOrElse(0.0) shouldBe 0.9
  }

  it should "add consolidation metadata" in {
    val manager = createManager()

    val result = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), None)
      m3 <- m2.recordUserFact("Fact 3", Some("user-1"), None)

      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.userFacts, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    (memories should have).length(1)

    val memory = memories.head
    memory.getMetadata("consolidated_from") shouldBe Some("3")
    memory.getMetadata("consolidation_method") shouldBe Some("llm_summary")
    memory.getMetadata("consolidated_at") should not be None
    memory.getMetadata("original_ids") should not be None
  }

  it should "consolidate entity facts" in {
    val manager  = createManager()
    val entityId = EntityId.fromName("Scala")

    val result = for {
      m1 <- manager.recordEntityFact(entityId, "Scala", "Created in 2004", "technology", None)
      m2 <- m1.recordEntityFact(entityId, "Scala", "Runs on JVM", "technology", None)
      m3 <- m2.recordEntityFact(entityId, "Scala", "Supports FP", "technology", None)

      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.entities, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    (memories should have).length(1)
    memories.head.content should include("Consolidated")
  }

  it should "consolidate knowledge entries" in {
    val manager = createManager()

    val result = for {
      m1 <- manager.recordKnowledge("Scala fact 1", "doc1", Map.empty)
      m2 <- m1.recordKnowledge("Scala fact 2", "doc1", Map.empty)
      m3 <- m2.recordKnowledge("Scala fact 3", "doc1", Map.empty)

      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.knowledge, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    (memories should have).length(1)
    memories.head.content should include("Consolidated")
  }

  it should "get conversation context" in {
    val manager = createManager()

    val result = for {
      populated <- manager.recordConversation(
        Seq(UserMessage("Hello"), AssistantMessage("Hi there")),
        "conv-1"
      )
      context <- populated.getConversationContext("conv-1", 10)
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get

    context should include("user")
    context should include("Hello")
  }

  it should "get entity context" in {
    val manager  = createManager()
    val entityId = EntityId.fromName("Scala")

    val result = for {
      populated <- manager.recordEntityFact(entityId, "Scala", "A language", "tech", None)
      context   <- populated.getEntityContext(entityId)
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get

    context should include("Scala")
    context should include("A language")
  }

  it should "get user context" in {
    val manager = createManager()

    val result = for {
      populated <- manager.recordUserFact("Likes Scala", Some("user-1"), None)
      context   <- populated.getUserContext(Some("user-1"))
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get

    context should include("Likes Scala")
  }

  it should "return stats" in {
    val manager = createManager()

    val result = for {
      m1    <- manager.recordUserFact("Fact 1", None, None)
      m2    <- m1.recordKnowledge("Knowledge 1", "source", Map.empty)
      stats <- m2.stats
    } yield stats

    result.isRight shouldBe true
    val stats = result.toOption.get

    stats.totalMemories shouldBe 2
    stats.byType should have size 2
  }

  it should "handle empty consolidation gracefully" in {
    val manager = createManager()

    val result = manager.consolidateMemories(
      olderThan = Instant.now().minus(1, ChronoUnit.DAYS),
      minCount = 3
    )

    result.isRight shouldBe true
  }
}
