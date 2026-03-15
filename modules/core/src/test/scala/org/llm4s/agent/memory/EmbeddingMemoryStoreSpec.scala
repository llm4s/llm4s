package org.llm4s.agent.memory

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import org.llm4s.types.Result

import java.time.Instant
import java.time.temporal.ChronoUnit

class EmbeddingMemoryStoreSpec extends AnyFlatSpec with Matchers {

  val now: Instant     = Instant.now()
  val hourAgo: Instant = now.minus(1, ChronoUnit.HOURS)
  val dayAgo: Instant  = now.minus(1, ChronoUnit.DAYS)

  def createMemory(content: String, memoryType: MemoryType = MemoryType.Conversation): Memory =
    Memory(MemoryId.generate(), content, memoryType, Map.empty, now)

  def createMockService(queryEmbedding: Array[Float] = Array(1f, 0f, 0f)): EmbeddingService =
    new EmbeddingService {
      override def embed(text: String): Result[Array[Float]] =
        Right(queryEmbedding)

      override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
        Right(texts.map(_ => queryEmbedding))

      override def dimensions: Int = queryEmbedding.length
    }

  def createFailingService(): EmbeddingService =
    new EmbeddingService {
      override def embed(text: String): Result[Array[Float]] =
        Left(ValidationError("Embedding failed", "query"))

      override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
        Left(ValidationError("Embedding failed", "query"))

      override def dimensions: Int = 3
    }

  "EmbeddingMemoryStore" should "be created with empty factory" in {
    val service = createMockService()
    val store   = EmbeddingMemoryStore.empty(service)
    store.size shouldBe 0
    store.all shouldBe empty
  }

  it should "be created with apply(inner, service) factory" in {
    val service = createMockService()
    val inner   = InMemoryStore.empty
    val store   = EmbeddingMemoryStore(inner, service)
    store.size shouldBe 0
    store.underlying shouldBe inner
  }

  it should "be created with apply(service, config) factory" in {
    val service = createMockService()
    val config  = MemoryStoreConfig(maxMemories = Some(100))
    val store   = EmbeddingMemoryStore(service, config)
    store.size shouldBe 0
  }

  it should "store a memory and return an EmbeddingMemoryStore" in {
    val service = createMockService()
    val store   = EmbeddingMemoryStore.empty(service)
    val memory  = createMemory("Test content")

    val result = store.store(memory)
    result.isRight shouldBe true
    result.toOption.get shouldBe a[EmbeddingMemoryStore]
    result.toOption.get.asInstanceOf[EmbeddingMemoryStore].size shouldBe 1
  }

  it should "get a memory by ID" in {
    val service = createMockService()
    val memory  = createMemory("Test content")

    val result = for {
      updated   <- EmbeddingMemoryStore.empty(service).store(memory)
      retrieved <- updated.get(memory.id)
    } yield retrieved

    result shouldBe Right(Some(memory))
  }

  it should "return None for non-existent memory ID" in {
    val service = createMockService()
    val result  = EmbeddingMemoryStore.empty(service).get(MemoryId("non-existent"))
    result shouldBe Right(None)
  }

  it should "recall memories with filter" in {
    val service = createMockService()
    val conv    = createMemory("Conv", MemoryType.Conversation)
    val entity  = createMemory("Entity", MemoryType.Entity)

    val result = for {
      s1    <- EmbeddingMemoryStore.empty(service).store(conv)
      s2    <- s1.store(entity)
      convs <- s2.recall(MemoryFilter.conversations)
    } yield convs

    result.map(_.size) shouldBe Right(1)
    result.map(_.head.content) shouldBe Right("Conv")
  }

  it should "delete a memory by ID and return EmbeddingMemoryStore" in {
    val service = createMockService()
    val memory  = createMemory("To delete")

    val result = for {
      s1        <- EmbeddingMemoryStore.empty(service).store(memory)
      s2        <- s1.delete(memory.id)
      retrieved <- s2.get(memory.id)
    } yield (s2, retrieved)

    result.isRight shouldBe true
    val (store, retrieved) = result.toOption.get
    store shouldBe a[EmbeddingMemoryStore]
    retrieved shouldBe None
  }

  it should "delete memories matching a filter and return EmbeddingMemoryStore" in {
    val service = createMockService()
    val conv    = createMemory("Conv", MemoryType.Conversation)
    val entity  = createMemory("Entity", MemoryType.Entity)

    val result = for {
      s1    <- EmbeddingMemoryStore.empty(service).store(conv)
      s2    <- s1.store(entity)
      s3    <- s2.deleteMatching(MemoryFilter.conversations)
      count <- s3.count()
    } yield (s3, count)

    result.isRight shouldBe true
    val (store, count) = result.toOption.get
    store shouldBe a[EmbeddingMemoryStore]
    count shouldBe 1
  }

  it should "update a memory and return EmbeddingMemoryStore" in {
    val service  = createMockService()
    val original = createMemory("Original")

    val result = for {
      s1      <- EmbeddingMemoryStore.empty(service).store(original)
      s2      <- s1.update(original.id, _.withImportance(0.9))
      updated <- s2.get(original.id)
    } yield (s2, updated.flatMap(_.importance))

    result.isRight shouldBe true
    val (store, importance) = result.toOption.get
    store shouldBe a[EmbeddingMemoryStore]
    importance shouldBe Some(0.9)
  }

  it should "count memories with filter" in {
    val service = createMockService()
    val conv1   = createMemory("Conv 1", MemoryType.Conversation)
    val conv2   = createMemory("Conv 2", MemoryType.Conversation)
    val entity  = createMemory("Entity", MemoryType.Entity)

    val result = for {
      s1    <- EmbeddingMemoryStore.empty(service).store(conv1)
      s2    <- s1.store(conv2)
      s3    <- s2.store(entity)
      count <- s3.count(MemoryFilter.conversations)
    } yield count

    result shouldBe Right(2)
  }

  it should "clear all memories and return EmbeddingMemoryStore" in {
    val service = createMockService()
    val memory1 = createMemory("Memory 1")
    val memory2 = createMemory("Memory 2")

    val result = for {
      s1      <- EmbeddingMemoryStore.empty(service).store(memory1)
      s2      <- s1.store(memory2)
      cleared <- s2.clear()
      count   <- cleared.count()
    } yield (cleared, count)

    result.isRight shouldBe true
    val (store, count) = result.toOption.get
    store shouldBe a[EmbeddingMemoryStore]
    count shouldBe 0
  }

  it should "get recent memories with filter" in {
    val service = createMockService()
    val old     = Memory(MemoryId.generate(), "Old", MemoryType.Conversation, Map.empty, dayAgo)
    val recent  = Memory(MemoryId.generate(), "Recent", MemoryType.Conversation, Map.empty, now)

    val result = for {
      s1      <- EmbeddingMemoryStore.empty(service).store(old)
      s2      <- s1.store(recent)
      recents <- s2.recent(limit = 1)
    } yield recents

    result.map(_.map(_.content)) shouldBe Right(Seq("Recent"))
  }

  it should "return empty results for blank search queries" in {
    val service = createMockService()
    val memory  = createMemory("Scala programming")

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(memory)
      scored <- s1.search("   ", topK = 10)
    } yield scored

    result shouldBe Right(Seq.empty)
  }

  it should "use keyword search when no embeddings are available" in {
    val service = createMockService()
    val memory1 = createMemory("Scala is a programming language")
    val memory2 = createMemory("Java runs on the JVM")

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(memory1)
      s2     <- s1.store(memory2)
      scored <- s2.search("Scala", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.size shouldBe 1
    scored.head.memory.content shouldBe "Scala is a programming language"
  }

  it should "use embedding similarity when memories have embeddings" in {
    val service = createMockService(Array(1f, 0f, 0f))
    val memoryA = createMemory("A").withEmbedding(Array(1f, 0f, 0f))
    val memoryB = createMemory("B").withEmbedding(Array(0f, 1f, 0f))
    val memoryC = createMemory("C").withEmbedding(Array(-1f, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(memoryA)
      s2     <- s1.store(memoryB)
      s3     <- s2.store(memoryC)
      scored <- s3.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.map(_.memory.content) shouldBe Seq("A", "B", "C")
    scored.head.score shouldBe 1.0 +- 1e-6
    scored(1).score shouldBe 0.5 +- 1e-6
    scored(2).score shouldBe 0.0 +- 1e-6
  }

  it should "fall back to keyword search when embedding service fails" in {
    val failingService = createFailingService()
    val memory         = createMemory("Scala programming").withEmbedding(Array(1f, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(failingService).store(memory)
      scored <- s1.search("Scala", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.size shouldBe 1
    scored.head.memory.content shouldBe "Scala programming"
  }

  it should "fall back to keyword search when no embedding candidates after filtering" in {
    val service = createMockService(Array(1f, 0f, 0f))
    // Memory with embedding of different dimension
    val memory = createMemory("Scala programming").withEmbedding(Array(1f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(memory)
      scored <- s1.search("Scala", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.size shouldBe 1
    scored.head.memory.content shouldBe "Scala programming"
  }

  it should "skip memories with NaN in embeddings" in {
    val service = createMockService(Array(1f, 0f, 0f))
    val good    = createMemory("Good").withEmbedding(Array(1f, 0f, 0f))
    val bad     = createMemory("Bad").withEmbedding(Array(Float.NaN, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(good)
      s2     <- s1.store(bad)
      scored <- s2.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.map(_.memory.content) shouldBe Seq("Good")
  }

  it should "skip memories with Infinity in embeddings" in {
    val service = createMockService(Array(1f, 0f, 0f))
    val good    = createMemory("Good").withEmbedding(Array(1f, 0f, 0f))
    val bad     = createMemory("Bad").withEmbedding(Array(Float.PositiveInfinity, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(good)
      s2     <- s1.store(bad)
      scored <- s2.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.map(_.memory.content) shouldBe Seq("Good")
  }

  it should "skip search when query embedding contains NaN" in {
    val nanService = new EmbeddingService {
      override def embed(text: String)            = Right(Array(Float.NaN, 0f, 0f))
      override def embedBatch(texts: Seq[String]) = Right(texts.map(_ => Array(Float.NaN, 0f, 0f)))
      override def dimensions: Int                = 3
    }
    val memory = createMemory("Test").withEmbedding(Array(1f, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(nanService).store(memory)
      scored <- s1.search("query", topK = 10)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    // Falls back to keyword search which should return empty (no keyword match)
    scored shouldBe empty
  }

  it should "respect topK limit in search results" in {
    val service  = createMockService(Array(1f, 0f, 0f))
    val memories = (1 to 10).map(i => createMemory(s"Memory $i").withEmbedding(Array(1f, 0f, 0f)))

    val result = memories
      .foldLeft[Result[MemoryStore]](
        Right(EmbeddingMemoryStore.empty(service))
      ) { case (acc, mem) =>
        acc.flatMap(_.store(mem))
      }
      .flatMap(_.search("query", topK = 5))

    result.isRight shouldBe true
    result.toOption.get.size shouldBe 5
  }

  it should "apply filter before embedding search" in {
    val service = createMockService(Array(1f, 0f, 0f))
    val conv    = createMemory("Conv", MemoryType.Conversation).withEmbedding(Array(1f, 0f, 0f))
    val entity  = createMemory("Entity", MemoryType.Entity).withEmbedding(Array(1f, 0f, 0f))

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(conv)
      s2     <- s1.store(entity)
      scored <- s2.search("query", topK = 10, MemoryFilter.conversations)
    } yield scored

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored.size shouldBe 1
    scored.head.memory.content shouldBe "Conv"
  }

  it should "handle keyword search with empty query terms" in {
    val service = createMockService()
    val memory  = createMemory("Test content")

    val result = for {
      s1     <- EmbeddingMemoryStore.empty(service).store(memory)
      scored <- s1.search("", topK = 10)
    } yield scored

    result shouldBe Right(Seq.empty)
  }

  it should "expose underlying InMemoryStore" in {
    val service = createMockService()
    val inner   = InMemoryStore.empty
    val store   = EmbeddingMemoryStore(inner, service)

    store.underlying shouldBe inner
  }

  it should "return all memories via all accessor" in {
    val service = createMockService()
    val memory1 = createMemory("Memory 1")
    val memory2 = createMemory("Memory 2")

    val result = for {
      s1 <- EmbeddingMemoryStore.empty(service).store(memory1)
      s2 <- s1.store(memory2)
    } yield s2

    result.isRight shouldBe true
    val store = result.toOption.get.asInstanceOf[EmbeddingMemoryStore]
    store.all.size shouldBe 2
  }
}
