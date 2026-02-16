package org.llm4s.agent.memory

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit

class InMemoryStoreSpec extends AnyFlatSpec with Matchers {

  val now: Instant     = Instant.now()
  val hourAgo: Instant = now.minus(1, ChronoUnit.HOURS)
  val dayAgo: Instant  = now.minus(1, ChronoUnit.DAYS)

  def createMemory(content: String, memoryType: MemoryType = MemoryType.Conversation): Memory =
    Memory(MemoryId.generate(), content, memoryType, Map.empty, now)

  "InMemoryStore" should "start empty" in {
    val store = InMemoryStore.empty
    store.size shouldBe 0
    store.all shouldBe empty
  }

  it should "store and retrieve a memory by ID" in {
    val memory = createMemory("Test content")
    val result = for {
      updated   <- InMemoryStore.empty.store(memory)
      retrieved <- updated.get(memory.id)
    } yield retrieved

    result shouldBe Right(Some(memory))
  }

  it should "return None for non-existent memory ID" in {
    val result = InMemoryStore.empty.get(MemoryId("non-existent"))
    result shouldBe Right(None)
  }

  it should "store multiple memories" in {
    val memory1 = createMemory("Content 1")
    val memory2 = createMemory("Content 2")
    val memory3 = createMemory("Content 3")

    val result = for {
      s1    <- InMemoryStore.empty.store(memory1)
      s2    <- s1.store(memory2)
      s3    <- s2.store(memory3)
      count <- s3.count()
    } yield count

    result shouldBe Right(3)
  }

  it should "recall memories with filter" in {
    val conv1  = createMemory("Conv 1", MemoryType.Conversation)
    val conv2  = createMemory("Conv 2", MemoryType.Conversation)
    val entity = createMemory("Entity", MemoryType.Entity)

    val result = for {
      s1    <- InMemoryStore.empty.store(conv1)
      s2    <- s1.store(conv2)
      s3    <- s2.store(entity)
      convs <- s3.recall(MemoryFilter.conversations)
    } yield convs

    result.map(_.size) shouldBe Right(2)
    result.map(_.map(_.memoryType).toSet) shouldBe Right(Set(MemoryType.Conversation))
  }

  it should "respect limit in recall" in {
    val memories = (1 to 10).map(i => createMemory(s"Memory $i"))

    val result = for {
      store    <- InMemoryStore.withMemories(memories)
      recalled <- store.recall(MemoryFilter.All, limit = 5)
    } yield recalled

    result.map(_.size) shouldBe Right(5)
  }

  it should "delete a memory by ID" in {
    val memory = createMemory("To delete")

    val result = for {
      s1        <- InMemoryStore.empty.store(memory)
      s2        <- s1.delete(memory.id)
      retrieved <- s2.get(memory.id)
    } yield retrieved

    result shouldBe Right(None)
  }

  it should "delete memories matching a filter" in {
    val conv   = createMemory("Conversation", MemoryType.Conversation)
    val entity = createMemory("Entity", MemoryType.Entity)

    val result = for {
      s1    <- InMemoryStore.empty.store(conv)
      s2    <- s1.store(entity)
      s3    <- s2.deleteMatching(MemoryFilter.conversations)
      count <- s3.count()
    } yield count

    result shouldBe Right(1)
  }

  it should "update a memory" in {
    val original = createMemory("Original")

    val result = for {
      s1      <- InMemoryStore.empty.store(original)
      s2      <- s1.update(original.id, _.withImportance(0.9))
      updated <- s2.get(original.id)
    } yield updated.flatMap(_.importance)

    result shouldBe Right(Some(0.9))
  }

  it should "return error when updating non-existent memory" in {
    val result = InMemoryStore.empty.update(MemoryId("non-existent"), identity)
    result.isLeft shouldBe true
  }

  it should "search memories by keyword" in {
    val memory1 = createMemory("Scala is a programming language")
    val memory2 = createMemory("Java runs on the JVM")
    val memory3 = createMemory("Scala also runs on the JVM")

    val result = for {
      store  <- InMemoryStore.withMemories(Seq(memory1, memory2, memory3))
      scored <- store.search("Scala JVM", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.size shouldBe 3

    // "Scala also runs on the JVM" should score highest (matches both terms)
    scored.head.memory.content should include("Scala")
    scored.head.memory.content should include("JVM")
  }

  it should "return empty results for blank search queries" in {
    val memory1 = createMemory("Scala is a programming language")
    val memory2 = createMemory("Java runs on the JVM")

    val result = for {
      store  <- InMemoryStore.withMemories(Seq(memory1, memory2))
      scored <- store.search("   ", topK = 10)
    } yield scored

    result shouldBe Right(Seq.empty)
  }

  it should "use embedding similarity search when embeddings and embedding service are available" in {
    val service = new EmbeddingService {
      override def embed(text: String) =
        text match {
          case "query" => Right(Array[Float](1f, 0f, 0f))
          case other   => Left(ValidationError(s"Unknown text: $other", "text"))
        }

      override def embedBatch(texts: Seq[String]) =
        Right(texts.map(t => embed(t).getOrElse(Array.emptyFloatArray)))

      override def dimensions: Int = 3
    }

    val memoryA = createMemory("A").withEmbedding(Array[Float](1f, 0f, 0f))
    val memoryB = createMemory("B").withEmbedding(Array[Float](0f, 1f, 0f))
    val memoryC = createMemory("C").withEmbedding(Array[Float](-1f, 0f, 0f))

    val result = for {
      s1     <- InMemoryStore.withEmbeddingService(service).store(memoryA)
      s2     <- s1.store(memoryB)
      s3     <- s2.store(memoryC)
      scored <- s3.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get

    // Keyword search would return empty here ("query" doesn't appear in any memory),
    // so non-empty results confirm the embedding path is being used.
    scored.map(_.memory.content) shouldBe Seq("A", "B", "C")
    scored.head.score shouldBe 1.0 +- 1e-6
    scored(1).score shouldBe 0.5 +- 1e-6
    scored(2).score shouldBe 0.0 +- 1e-6
  }

  it should "return recent memories in descending order" in {
    val old    = Memory(MemoryId.generate(), "Old", MemoryType.Conversation, Map.empty, dayAgo)
    val medium = Memory(MemoryId.generate(), "Medium", MemoryType.Conversation, Map.empty, hourAgo)
    val recent = Memory(MemoryId.generate(), "Recent", MemoryType.Conversation, Map.empty, now)

    val result = for {
      store   <- InMemoryStore.withMemories(Seq(old, medium, recent))
      recents <- store.recent(limit = 2)
    } yield recents

    result.map(_.map(_.content)) shouldBe Right(Seq("Recent", "Medium"))
  }

  it should "get entity memories" in {
    val entityId     = EntityId.fromName("Test Entity")
    val entityMemory = Memory.forEntity(entityId, "Test Entity", "A fact", "thing")
    val otherMemory  = createMemory("Other")

    val result = for {
      s1       <- InMemoryStore.empty.store(entityMemory)
      s2       <- s1.store(otherMemory)
      entities <- s2.getEntityMemories(entityId)
    } yield entities

    result.map(_.size) shouldBe Right(1)
    result.map(_.head.content) shouldBe Right("A fact")
  }

  it should "get conversation history in chronological order" in {
    val msg1 = Memory(
      MemoryId.generate(),
      "First",
      MemoryType.Conversation,
      Map("conversation_id" -> "conv-1"),
      dayAgo
    )
    val msg2 = Memory(
      MemoryId.generate(),
      "Second",
      MemoryType.Conversation,
      Map("conversation_id" -> "conv-1"),
      hourAgo
    )
    val msg3 = Memory(
      MemoryId.generate(),
      "Third",
      MemoryType.Conversation,
      Map("conversation_id" -> "conv-1"),
      now
    )

    val result = for {
      store <- InMemoryStore.withMemories(Seq(msg1, msg3, msg2)) // Insert out of order
      conv  <- store.getConversation("conv-1")
    } yield conv

    result.map(_.map(_.content)) shouldBe Right(Seq("First", "Second", "Third"))
  }

  it should "clear all memories" in {
    val result = for {
      store   <- InMemoryStore.withMemories((1 to 5).map(i => createMemory(s"Memory $i")))
      cleared <- store.clear()
      count   <- cleared.count()
    } yield count

    result shouldBe Right(0)
  }

  it should "check if memory exists" in {
    val memory = createMemory("Test")

    val result = for {
      store     <- InMemoryStore.empty.store(memory)
      exists    <- store.exists(memory.id)
      notExists <- store.exists(MemoryId("non-existent"))
    } yield (exists, notExists)

    result shouldBe Right((true, false))
  }

  it should "enforce max memories limit" in {
    val config = MemoryStoreConfig(maxMemories = Some(5))
    val store  = InMemoryStore(config)

    val memories = (1 to 10).map { i =>
      Memory(
        MemoryId.generate(),
        s"Memory $i",
        MemoryType.Conversation,
        Map.empty,
        now.plus(i, ChronoUnit.MINUTES)
      )
    }

    val result = memories.foldLeft[Either[_, MemoryStore]](Right(store))((acc, m) => acc.flatMap(_.store(m)))

    result.map {
      case ims: InMemoryStore => ims.size
      case _                  => -1
    } shouldBe Right(5)

    // Should keep the 5 most recent
    result.map {
      case ims: InMemoryStore => ims.all.map(_.content).toSet
      case _                  => Set.empty[String]
    } shouldBe Right(Set("Memory 6", "Memory 7", "Memory 8", "Memory 9", "Memory 10"))
  }

  it should "get important memories" in {
    val low     = createMemory("Low importance").withImportance(0.2)
    val high    = createMemory("High importance").withImportance(0.8)
    val noScore = createMemory("No score")

    val result = for {
      store     <- InMemoryStore.withMemories(Seq(low, high, noScore))
      important <- store.important(threshold = 0.5)
    } yield important

    result.map(_.size) shouldBe Right(1)
    result.map(_.head.content) shouldBe Right("High importance")
  }

  it should "reject ID changes in update function" in {
    val original = createMemory("Original")
    val newId    = MemoryId.generate()

    val result = for {
      store   <- InMemoryStore.empty.store(original)
      updated <- store.update(original.id, _.copy(id = newId))
    } yield updated

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("ID")
  }

  it should "handle NaN embeddings without throwing" in {
    val service = new EmbeddingService {
      override def embed(text: String)            = Right(Array[Float](1f, 0f, 0f))
      override def embedBatch(texts: Seq[String]) = Right(texts.map(_ => embed("").getOrElse(Array.emptyFloatArray)))
      override def dimensions: Int                = 3
    }

    // Store a memory with NaN in the embedding
    val memoryWithNaN = createMemory("Test").withEmbedding(Array[Float](Float.NaN, 0f, 0f))
    val normalMemory  = createMemory("Normal").withEmbedding(Array[Float](1f, 0f, 0f))

    val result = for {
      s1     <- InMemoryStore.withEmbeddingService(service).store(memoryWithNaN)
      s2     <- s1.store(normalMemory)
      scored <- s2.search("query", topK = 10)
    } yield scored

    // Should not throw, should return only valid memories
    result.isRight shouldBe true
    val scored = result.toOption.get
    // The NaN memory should be skipped, only normal memory returned
    scored.map(_.memory.content) shouldBe Seq("Normal")
  }

  it should "handle Infinity embeddings without throwing" in {
    val service = new EmbeddingService {
      override def embed(text: String)            = Right(Array[Float](1f, 0f, 0f))
      override def embedBatch(texts: Seq[String]) = Right(texts.map(_ => embed("").getOrElse(Array.emptyFloatArray)))
      override def dimensions: Int                = 3
    }

    val memoryWithInf = createMemory("Test").withEmbedding(Array[Float](Float.PositiveInfinity, 0f, 0f))
    val normalMemory  = createMemory("Normal").withEmbedding(Array[Float](1f, 0f, 0f))

    val result = for {
      s1     <- InMemoryStore.withEmbeddingService(service).store(memoryWithInf)
      s2     <- s1.store(normalMemory)
      scored <- s2.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.map(_.memory.content) shouldBe Seq("Normal")
  }

  it should "fall back to keyword search when embedding fails" in {
    val failingService = new EmbeddingService {
      override def embed(text: String)            = Left(ValidationError("embedding-failed", "Test failure"))
      override def embedBatch(texts: Seq[String]) = Left(ValidationError("embedding-failed", "Test failure"))
      override def dimensions: Int                = 3
    }

    val memory1 = createMemory("Scala programming").withEmbedding(Array[Float](1f, 0f, 0f))
    val memory2 = createMemory("Java development").withEmbedding(Array[Float](0f, 1f, 0f))

    val result = for {
      s1     <- InMemoryStore.withEmbeddingService(failingService).store(memory1)
      s2     <- s1.store(memory2)
      scored <- s2.search("Scala", topK = 10)
    } yield scored

    // Should succeed with keyword search fallback
    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.nonEmpty shouldBe true
    // First result should contain "Scala" (keyword match)
    scored.head.memory.content should include("Scala")
  }
}
