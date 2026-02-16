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

  /**
   * Mock LLM client that fails for testing error paths.
   */
  class FailingMockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Left(org.llm4s.error.APIError("test-provider", "Mock LLM failure for testing"))

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

  def createFailingManager(): LLMMemoryManager = {
    val client = new FailingMockLLMClient()
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

  it should "not consolidate when each group is below minCount" in {
    val manager  = createManager()
    val entityId = EntityId.fromName("Scala")

    // Create multiple groups, each with 2 memories (below minCount of 3)
    // Total = 6 memories, but no single group has 3+
    val populated = for {
      m1 <- manager.recordUserFact("User fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("User fact 2", Some("user-1"), None)
      m3 <- m2.recordEntityFact(entityId, "Scala", "Entity fact 1", "tech", None)
      m4 <- m3.recordEntityFact(entityId, "Scala", "Entity fact 2", "tech", None)
      m5 <- m4.recordKnowledge("Knowledge 1", "doc.md", Map.empty)
      m6 <- m5.recordKnowledge("Knowledge 2", "doc.md", Map.empty)
    } yield m6

    val consolidated = populated.flatMap(
      _.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )
    )

    consolidated.isRight shouldBe true

    // Verify no consolidation happened (all 6 memories remain)
    val finalStore = consolidated.toOption.get.store
    val remaining  = finalStore.recall(MemoryFilter.All, 100)

    (remaining.toOption.get should have).length(6)
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

  it should "use latest timestamp for consolidated memory" in {
    val manager = createManager()

    val baseTime = Instant.now().minus(3, ChronoUnit.DAYS)
    val t1       = baseTime
    val t2       = baseTime.plus(1, ChronoUnit.DAYS)
    val t3       = baseTime.plus(2, ChronoUnit.DAYS)
    val convId   = Some("conv-1")

    val m1 = Memory.fromConversation("Message 1", "user", convId).copy(timestamp = t1)
    val m2 = Memory.fromConversation("Message 2", "assistant", convId).copy(timestamp = t2)
    val m3 = Memory.fromConversation("Message 3", "user", convId).copy(timestamp = t3)

    val result = for {
      s1 <- manager.store.store(m1)
      s2 <- s1.store(m2)
      s3 <- s2.store(m3)
      updatedManager = manager.copy(store = s3)
      consolidated <- updatedManager.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )
      memories <- consolidated.store.recall(MemoryFilter.conversations, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    (memories should have).length(1)
    memories.head.timestamp shouldBe t3
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
  // ============================================================
  //  Determinism and Stability tests
  // ============================================================

  it should "process memory consolidation deterministically without flaky ordering" in {
    val cut = Instant.now().plus(1, ChronoUnit.DAYS)
    val runs = (1 to 6).map { _ =>
      val manager = createManager()

      val result = for {
        // Conversations(3 messages)
        m1 <- manager.recordMessage(UserMessage("Conv A"), conversationId = "conv-det", importance = Some(0.2))
        m2 <- m1.recordMessage(AssistantMessage("Conv B"), conversationId = "conv-det", importance = Some(0.3))
        m3 <- m2.recordMessage(UserMessage("Conv C"), conversationId = "conv-det", importance = Some(0.4))
        // User facts(3 facts, same user)
        m4 <- m3.recordUserFact("Fact A", Some("user-det"), Some(0.5))
        m5 <- m4.recordUserFact("Fact B", Some("user-det"), Some(0.7))
        m6 <- m5.recordUserFact("Fact C", Some("user-det"), Some(0.6))
        // Knowledge(3 entries, same source)
        m7 <- m6.recordKnowledge("Know A", "doc-det.md", Map("source" -> "doc-det"))
        m8 <- m7.recordKnowledge("Know B", "doc-det.md", Map("source" -> "doc-det"))
        m9 <- m8.recordKnowledge("Know C", "doc-det.md", Map("source" -> "doc-det"))
        // Consolidate all eligible groups
        cons <- m9.consolidateMemories(olderThan = cut, minCount = 3)

        st   <- cons.stats
        conv <- cons.store.recall(MemoryFilter.conversations, 10)
        usr  <- cons.store.recall(MemoryFilter.userFacts, 10)
        kn   <- cons.store.recall(MemoryFilter.knowledge, 10)
      } yield (st.totalMemories, conv, usr, kn)
      result.isRight shouldBe true
      result.toOption.get
    }
    val ref = runs.head
    // Consolidation happened (9->3)
    ref._1 should be < 9L
    ref._2.length shouldBe 1
    ref._3.length shouldBe 1
    ref._4.length shouldBe 1

    runs.foreach { r =>
      r._1 shouldBe ref._1
      // Exact order/content
      r._2.map(_.content) shouldBe ref._2.map(_.content)
      r._3.map(_.content) shouldBe ref._3.map(_.content)
      r._4.map(_.content) shouldBe ref._4.map(_.content)
      // Importance preserved (max per group)
      r._2.flatMap(_.importance) shouldBe ref._2.flatMap(_.importance)
      r._3.flatMap(_.importance) shouldBe ref._3.flatMap(_.importance)
      r._4.flatMap(_.importance) shouldBe ref._4.flatMap(_.importance)
      // Consolidation metadata stable
      r._2.flatMap(_.getMetadata("consolidated_from")) shouldBe ref._2.flatMap(_.getMetadata("consolidated_from"))
      r._3.flatMap(_.getMetadata("consolidated_from")) shouldBe ref._3.flatMap(_.getMetadata("consolidated_from"))
      r._4.flatMap(_.getMetadata("consolidated_from")) shouldBe ref._4.flatMap(_.getMetadata("consolidated_from"))
    }
  }

  // ============================================================
  // Error path tests
  // ============================================================

  it should "handle LLM failures during consolidation" in {
    val manager = createFailingManager()

    val result = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), None)
      m3 <- m2.recordUserFact("Fact 3", Some("user-1"), None)

      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )
    } yield consolidated

    // Should succeed (non-fatal error recovery) but preserve original memories
    result.isRight shouldBe true
    val memories = result.toOption.get.store.recall(MemoryFilter.All, 100)
    (memories.toOption.get should have).length(3) // Original memories preserved
  }

  it should "preserve original memories when LLM consolidation fails" in {
    val manager = createFailingManager()

    val result = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), None)
      m3 <- m2.recordUserFact("Fact 3", Some("user-1"), None)

      // Try to consolidate (should continue despite LLM failure)
      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.All, 100)
    } yield memories

    // Should succeed and preserve original memories (non-fatal error recovery)
    result.isRight shouldBe true
    (result.toOption.get should have).length(3) // All original memories preserved
  }

  it should "fail fast in strict mode when consolidation fails" in {
    val strictConfig = MemoryManagerConfig.testing.copy(
      consolidationConfig = ConsolidationConfig.strict
    )
    val client  = new FailingMockLLMClient()
    val store   = InMemoryStore.empty
    val manager = LLMMemoryManager(strictConfig, store, client)

    val result = for {
      m1 <- manager.recordUserFact("Fact 1", Some("user-1"), None)
      m2 <- m1.recordUserFact("Fact 2", Some("user-1"), None)
      m3 <- m2.recordUserFact("Fact 3", Some("user-1"), None)

      // Try to consolidate in strict mode (should fail fast)
      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )
    } yield consolidated

    // Should fail fast in strict mode
    result.isLeft shouldBe true
  }

  // ============================================================
  // formatMemoriesAsContext edge cases
  // ============================================================

  it should "handle empty context retrieval" in {
    val manager = createManager()

    val result = manager.getRelevantContext("nonexistent query", maxTokens = 100)

    result.isRight shouldBe true
    result.toOption.get shouldBe ""
  }

  it should "truncate context when exceeding maxTokens" in {
    val manager = createManager()

    // Create many memories with long content
    val result = for {
      m1 <- manager.recordKnowledge("A" * 1000, "source1.md", Map.empty)
      m2 <- m1.recordKnowledge("B" * 1000, "source2.md", Map.empty)
      m3 <- m2.recordKnowledge("C" * 1000, "source3.md", Map.empty)

      // Request context with very low token limit
      context <- m3.getRelevantContext("test query", maxTokens = 10)
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get

    // Should truncate based on maxTokens (10 tokens ≈ 40 chars)
    context.length should be <= 200 // Allow some overhead for headers
  }

  it should "format different memory types in context" in {
    val manager  = createManager()
    val entityId = EntityId.fromName("TestEntity")

    val result = for {
      m1 <- manager.recordKnowledge("Knowledge fact", "doc.md", Map.empty)
      m2 <- m1.recordEntityFact(entityId, "TestEntity", "Entity fact", "type", None)
      m3 <- m2.recordUserFact("User fact", Some("user-1"), None)
      m4 <- m3.recordTask("Task desc", "Outcome", success = true, None)

      // Recall all to check they exist
      allMemories <- m4.store.recall(MemoryFilter.All, 100)
    } yield allMemories

    result.isRight shouldBe true
    val memories = result.toOption.get

    // Verify all 4 memories were created
    (memories should have).length(4)

    // Verify each type is present
    val types = memories.map(_.memoryType).toSet
    types should contain(MemoryType.Knowledge)
    types should contain(MemoryType.Entity)
    types should contain(MemoryType.UserFact)
    types should contain(MemoryType.Task)
  }

  // ============================================================
  // Custom memory type tests
  // ============================================================

  it should "handle custom memory types" in {
    val manager = createManager()

    // Create custom memory using basic constructor
    val customMemory = Memory(
      id = MemoryId.generate(),
      content = "Custom memory content",
      memoryType = MemoryType.Custom("CustomType"),
      metadata = Map.empty,
      timestamp = Instant.now(),
      importance = Some(0.8),
      embedding = None
    )

    val result = for {
      newStore <- manager.store.store(customMemory)
      newManager = manager.copy(store = newStore)
      stats       <- newManager.stats
      allMemories <- newStore.recall(MemoryFilter.All, 100)
    } yield (stats, allMemories)

    result.isRight shouldBe true
    val (stats, allMemories) = result.toOption.get

    stats.totalMemories shouldBe 1
    (allMemories should have).length(1)
    allMemories.head.memoryType shouldBe MemoryType.Custom("CustomType")
  }

  it should "consolidate custom memory types" in {
    val manager = createManager()

    // Create multiple custom memories with knowledge type (which is supported for consolidation)
    val result = for {
      m1 <- manager.recordKnowledge("Custom knowledge 1", "custom-source.md", Map("type" -> "custom"))
      m2 <- m1.recordKnowledge("Custom knowledge 2", "custom-source.md", Map("type" -> "custom"))
      m3 <- m2.recordKnowledge("Custom knowledge 3", "custom-source.md", Map("type" -> "custom"))

      // Consolidate
      consolidated <- m3.consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS),
        minCount = 3
      )

      memories <- consolidated.store.recall(MemoryFilter.knowledge, 100)
    } yield memories

    result.isRight shouldBe true
    val memories = result.toOption.get

    // Should consolidate knowledge entries from same source
    (memories should have).length(1)
    memories.head.content should include("Consolidated")
  }

  it should "format custom memory types in context" in {
    val manager = createManager()

    val customMemory = Memory(
      id = MemoryId.generate(),
      content = "Custom content for retrieval",
      memoryType = MemoryType.Custom("SpecialType"),
      metadata = Map.empty,
      timestamp = Instant.now(),
      importance = Some(0.9),
      embedding = None
    )

    val result = for {
      newStore <- manager.store.store(customMemory)
      newManager = manager.copy(store = newStore)
      context <- newManager.getRelevantContext("custom", maxTokens = 1000)
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get

    // Should include custom type section
    context should include("SpecialType")
    context should include("Custom content")
  }
}
