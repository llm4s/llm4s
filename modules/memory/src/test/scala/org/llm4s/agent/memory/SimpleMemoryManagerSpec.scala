package org.llm4s.agent.memory

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SimpleMemoryManagerSpec extends AnyFlatSpec with Matchers {

  "SimpleMemoryManager" should "start with empty stats" in {
    val manager = SimpleMemoryManager.empty
    val result  = manager.stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(0)
  }

  it should "record a user message" in {
    val manager = SimpleMemoryManager.empty
    val message = UserMessage("Hello, how are you?")

    val result = for {
      updated <- manager.recordMessage(message, "conv-1", None)
      stats   <- updated.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(1)
  }

  it should "record different message types" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1    <- manager.recordMessage(UserMessage("User message"), "conv-1", None)
      m2    <- m1.recordMessage(AssistantMessage("Assistant response"), "conv-1", None)
      m3    <- m2.recordMessage(SystemMessage("System prompt"), "conv-1", None)
      m4    <- m3.recordMessage(ToolMessage("Tool result", "call-123"), "conv-1", None)
      stats <- m4.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(4)
  }

  it should "record a complete conversation" in {
    val manager = SimpleMemoryManager.empty
    val messages = Seq(
      SystemMessage("You are helpful"),
      UserMessage("Hello"),
      AssistantMessage("Hi there!"),
      UserMessage("What's 2+2?"),
      AssistantMessage("4")
    )

    val result = for {
      updated <- manager.recordConversation(messages, "conv-1")
      stats   <- updated.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(5)
  }

  it should "record entity facts" in {
    val manager  = SimpleMemoryManager.empty
    val entityId = EntityId.fromName("John Doe")

    val result = for {
      m1    <- manager.recordEntityFact(entityId, "John Doe", "Works at Anthropic", "person", None)
      m2    <- m1.recordEntityFact(entityId, "John Doe", "Lives in SF", "person", None)
      stats <- m2.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(2)
  }

  it should "record user facts" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1    <- manager.recordUserFact("Prefers dark mode", Some("user-1"), None)
      m2    <- m1.recordUserFact("Speaks English and French", Some("user-1"), None)
      stats <- m2.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(2)
  }

  it should "record knowledge" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      updated <- manager.recordKnowledge(
        "Scala is a JVM language",
        "docs/scala.md",
        Map("chapter" -> "intro")
      )
      stats <- updated.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(1)
  }

  it should "record task outcomes" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1    <- manager.recordTask("Deploy app", "Successfully deployed", success = true, None)
      m2    <- m1.recordTask("Run tests", "3 tests failed", success = false, None)
      stats <- m2.stats
    } yield stats

    result.isRight shouldBe true
    result.map(_.totalMemories) shouldBe Right(2)
  }

  it should "get conversation context" in {
    val manager = SimpleMemoryManager.empty
    val messages = Seq(
      UserMessage("Hello"),
      AssistantMessage("Hi there!")
    )

    val result = for {
      updated <- manager.recordConversation(messages, "conv-1")
      context <- updated.getConversationContext("conv-1")
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get
    context should include("user")
    context should include("Hello")
    context should include("assistant")
    context should include("Hi there!")
  }

  it should "get entity context" in {
    val manager  = SimpleMemoryManager.empty
    val entityId = EntityId.fromName("Scala")

    val result = for {
      m1      <- manager.recordEntityFact(entityId, "Scala", "Is a JVM language", "technology", None)
      m2      <- m1.recordEntityFact(entityId, "Scala", "Supports FP and OOP", "technology", None)
      context <- m2.getEntityContext(entityId)
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get
    context should include("Scala")
    context should include("JVM language")
    context should include("FP and OOP")
  }

  it should "get user context" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1      <- manager.recordUserFact("Likes coffee", Some("user-1"), None)
      m2      <- m1.recordUserFact("Works remotely", Some("user-1"), None)
      context <- m2.getUserContext(Some("user-1"))
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get
    context should include("coffee")
    context should include("remotely")
  }

  it should "get relevant context for a query" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1      <- manager.recordKnowledge("Scala is a programming language", "docs", Map.empty)
      m2      <- m1.recordKnowledge("Python is popular for ML", "docs", Map.empty)
      m3      <- m2.recordKnowledge("Scala runs on the JVM", "docs", Map.empty)
      context <- m3.getRelevantContext("Tell me about Scala")
    } yield context

    result.isRight shouldBe true
    val context = result.toOption.get
    // Should contain Scala-related memories
    context.toLowerCase should include("scala")
  }

  it should "return empty context when no memories exist" in {
    val manager = SimpleMemoryManager.empty

    val result = manager.getRelevantContext("anything")
    result shouldBe Right("")
  }

  it should "return empty conversation context for non-existent conversation" in {
    val manager = SimpleMemoryManager.empty

    val result = manager.getConversationContext("non-existent")
    result shouldBe Right("")
  }

  it should "track memory stats correctly" in {
    val manager  = SimpleMemoryManager.empty
    val entityId = EntityId.fromName("Test")

    val result = for {
      m1    <- manager.recordMessage(UserMessage("Hi"), "conv-1", None)
      m2    <- m1.recordMessage(AssistantMessage("Hello"), "conv-1", None)
      m3    <- m2.recordEntityFact(entityId, "Test", "Fact", "thing", None)
      m4    <- m3.recordKnowledge("Knowledge", "source", Map.empty)
      m5    <- m4.recordUserFact("User fact", None, None)
      m6    <- m5.recordTask("Task", "Done", success = true, None)
      stats <- m6.stats
    } yield stats

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.totalMemories shouldBe 6
    stats.byType.get(MemoryType.Conversation) shouldBe Some(2)
    stats.byType.get(MemoryType.Entity) shouldBe Some(1)
    stats.byType.get(MemoryType.Knowledge) shouldBe Some(1)
    stats.byType.get(MemoryType.UserFact) shouldBe Some(1)
    stats.byType.get(MemoryType.Task) shouldBe Some(1)
  }

  it should "use default importance from config" in {
    val config  = MemoryManagerConfig(defaultImportance = 0.7)
    val manager = SimpleMemoryManager(config)

    val result = for {
      updated  <- manager.recordMessage(UserMessage("Test"), "conv-1", None)
      memories <- updated.store.recall(MemoryFilter.All)
    } yield memories.head.importance

    result shouldBe Right(Some(0.7))
  }

  it should "use provided importance over default" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      updated  <- manager.recordMessage(UserMessage("Important"), "conv-1", Some(0.95))
      memories <- updated.store.recall(MemoryFilter.All)
    } yield memories.head.importance

    result shouldBe Right(Some(0.95))
  }

  it should "not change state when extractEntities is called" in {
    val manager = SimpleMemoryManager.empty

    val result = for {
      m1 <- manager.recordUserFact("User likes Scala", Some("user-1"), None)

      beforeStats <- m1.stats
      beforeAll   <- m1.store.recall(MemoryFilter.All, 100)

      m2 <- m1.extractEntities("Scala is a programming language", Some("conv-1"))

      afterStats <- m2.stats
      afterAll   <- m2.store.recall(MemoryFilter.All, 100)
    } yield (
      beforeStats.totalMemories,
      afterStats.totalMemories,
      beforeAll.length,
      afterAll.length
    )

    result.isRight shouldBe true
    val (beforeTotal, afterTotal, beforeCount, afterCount) = result.toOption.get

    afterTotal shouldBe beforeTotal
    afterCount shouldBe beforeCount
  }

  "SimpleMemoryManager.forTesting" should "create a testing instance" in {
    val manager = SimpleMemoryManager.forTesting
    manager.config.autoRecordMessages shouldBe false
    manager.config.autoExtractEntities shouldBe false
  }
}
