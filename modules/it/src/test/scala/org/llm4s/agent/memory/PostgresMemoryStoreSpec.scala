package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.util.Try
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret

/**
 * Integration tests for PostgresMemoryStore.
 *
 * Tier 2 (`@Docker`). CI's `Integration Tests` job runs this suite on every pull request
 * (`sbt testIntegration`): it starts a pgvector service container, sets
 * `POSTGRES_TEST_ENABLED=true` and `LLM4S_IT_STRICT=true` (so a missing flag would fail the
 * tests rather than skip them, see `Tier.require`).
 *
 * Locally the tests are skipped (cancelled) unless `POSTGRES_TEST_ENABLED=true` is set. To run them:
 *   1. Start Postgres: docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16
 *   2. Enable tests: export POSTGRES_TEST_ENABLED=true
 *   3. Run: sbt "it/testOnly org.llm4s.agent.memory.PostgresMemoryStoreSpec"
 *
 * The connection comes from `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and
 * `POSTGRES_PASSWORD`; the defaults are localhost:5432, database `postgres`, user `postgres`,
 * password `password`, which match the docker command above.
 */
@Docker
class PostgresMemoryStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private var store: PostgresMemoryStore = _
  private val tableName                  = s"test_memories_${System.currentTimeMillis()}"

  private val dbConfig = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = Secret(sys.env.getOrElse("POSTGRES_PASSWORD", "password")),
    tableName = tableName,
    maxPoolSize = 4
  )
  private val embeddingService = MockEmbeddingService.default

  override def beforeEach(): Unit =
    if (isEnabled) {
      store = PostgresMemoryStore(dbConfig, Some(embeddingService)).fold(e => fail(e.message), identity)
    }

  override def afterEach(): Unit =
    if (store != null) { Try(store.clear()); store.close() }

  private def skipIfDisabled(testBody: => Unit): Unit = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    testBody
  }

  it should "store and retrieve a conversation memory" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    val memory = Memory(
      id = id,
      content = "Hello, I am a test memory",
      memoryType = MemoryType.Conversation,
      metadata = Map("conversation_id" -> "conv-1")
    )

    store.store(memory).isRight shouldBe true

    store
      .get(id)
      .fold(
        e => fail(s"Get failed: ${e.message}"),
        {
          case Some(retrieved) =>
            retrieved.content shouldBe "Hello, I am a test memory"
            retrieved.metadata.get("conversation_id") shouldBe Some("conv-1")
          case None =>
            fail("Expected memory to be present, but got None")
        }
      )
  }

  it should "persist data across store instances" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "Persistence Check", MemoryType.Task)).isRight shouldBe true

    store.close()
    val store2 = PostgresMemoryStore(dbConfig).fold(e => fail(e.message), identity)

    store2
      .get(id)
      .fold(
        e => fail(s"Get failed on store2: ${e.message}"),
        {
          case Some(retrieved) =>
            retrieved.content shouldBe "Persistence Check"
          case None =>
            fail("Persistence check failed: Memory not found in new store instance")
        }
      )
    store2.close()
  }

  it should "perform semantic search" in skipIfDisabled {
    val applesEmbedding = embeddingService
      .embed("apples")
      .fold(
        e => fail(s"Test setup embedding failed: ${e.message}"),
        identity
      )
    val relevant = Memory(
      MemoryId("1"),
      "I like apples",
      MemoryType.Task,
      embedding = Some(applesEmbedding)
    )
    store.store(relevant)

    store
      .search("apple", 1, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results =>
          results match {
            case first +: _ => first.memory.content shouldBe "I like apples"
            case Nil        => fail("Expected at least one search result")
          }
      )
  }

  it should "search by keyword, not return the newest rows, when EmbeddingService is missing (#3030)" in
    skipIfDisabled {
      val storeNoEmb = PostgresMemoryStore(dbConfig, None).fold(e => fail(e.message), identity)
      storeNoEmb.store(Memory(MemoryId("fallback-1"), "test fallback", MemoryType.Task, Map.empty))
      storeNoEmb.store(Memory(MemoryId("fallback-2"), "unrelated newer note", MemoryType.Task, Map.empty))

      storeNoEmb.search("query", 5, MemoryFilter.All) shouldBe Right(Seq.empty)
      storeNoEmb.search("FALLBACK test", 5, MemoryFilter.All).map(_.map(s => s.memory.id.value -> s.score)) shouldBe
        Right(Seq("fallback-1" -> 1.0))
      storeNoEmb.search("fallback note", 5, MemoryFilter.All).map(_.map(s => s.memory.id.value -> s.score)) shouldBe
        Right(Seq("fallback-2" -> 0.5, "fallback-1" -> 0.5))
      storeNoEmb.search("fallback", 5, MemoryFilter.ByType(MemoryType.Knowledge)) shouldBe Right(Seq.empty)
      storeNoEmb.close()
    }

  it should "embed what a manager records, so getRelevantContext finds it (#3030)" in skipIfDisabled {
    val manager = SimpleMemoryManager.withStore(store)
    val context = for {
      m1  <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
      m2  <- m1.recordKnowledge("Postgres stores the memories", "notes")
      ctx <- m2.getRelevantContext("which programming language")
    } yield ctx
    context.fold(e => fail(e.message), identity) should include("Prefers Scala")
    store.recall(MemoryFilter.All, 10).map(_.forall(_.isEmbedded)) shouldBe Right(true)
  }

  it should "embed every write path: storeAll, replace and applyAtomically" in skipIfDisabled {
    store.storeAll(batch(3)).isRight shouldBe true
    store
      .replace(Seq(Memory(MemoryId("summary"), "summary note", MemoryType.Knowledge)), Seq(MemoryId("batch-1")))
      .isRight shouldBe true
    store
      .applyAtomically(
        AppliedWriteKey("scope", "write-1"),
        1L,
        Seq(Memory(MemoryId("applied"), "applied note", MemoryType.Knowledge)),
        Seq.empty,
        MemoryBounds()
      )
      .isRight shouldBe true
    store.recall(MemoryFilter.All, 10).map(ms => ms.map(_.id.value).toSet -> ms.forall(_.isEmbedded)) shouldBe
      Right(Set("batch-2", "batch-3", "summary", "applied") -> true)
  }

  it should "embed a memory again when update changes its content, and keep an embedding update supplies" in
    skipIfDisabled {
      val id = MemoryId("reembed")
      store.store(Memory(id, "first text", MemoryType.Knowledge)).isRight shouldBe true
      store.update(id, _.copy(content = "second text")).isRight shouldBe true
      val expected = embeddingService.embedBatch(Seq("second text")).toOption.get.head.toSeq
      store.get(id).map(_.flatMap(_.embedding).map(_.toSeq)) shouldBe Right(Some(expected))

      val own = Array.fill(embeddingService.dimensions)(0.5f)
      store.update(id, _.copy(content = "third text", embedding = Some(own))).isRight shouldBe true
      store.get(id).map(_.flatMap(_.embedding).map(_.toSeq)) shouldBe Right(Some(own.toSeq))
    }

  it should "embed the rows stored without an embedding with embedAll" in skipIfDisabled {
    val plain = PostgresMemoryStore(dbConfig, None).fold(e => fail(e.message), identity)
    plain.storeAll(batch(5)).isRight shouldBe true
    plain.embedAll().isLeft shouldBe true
    plain.close()

    store.embedAll(batchSize = 2) shouldBe Right(5)
    store.embedAll() shouldBe Right(0)
    store.recall(MemoryFilter.All, 10).map(_.forall(_.isEmbedded)) shouldBe Right(true)
  }

  it should "round-trip custom memory types, and read the spelling an older version wrote (#3031)" in
    skipIfDisabled {
      store.store(Memory(MemoryId("report"), "quarterly report", MemoryType.Custom("Report"))).isRight shouldBe true
      store.store(Memory(MemoryId("task"), "custom task", MemoryType.Custom("task"))).isRight shouldBe true
      store.store(Memory(MemoryId("legacy"), "legacy report", MemoryType.Custom("Report"))).isRight shouldBe true
      execute(s"UPDATE $tableName SET memory_type = 'Report' WHERE id = 'legacy'") // as written before #3031

      store.get(MemoryId("report")).map(_.map(_.memoryType)) shouldBe Right(Some(MemoryType.Custom("Report")))
      store.get(MemoryId("task")).map(_.map(_.memoryType)) shouldBe Right(Some(MemoryType.Custom("task")))
      store.get(MemoryId("legacy")).map(_.map(_.memoryType)) shouldBe Right(Some(MemoryType.Custom("Report")))
      store.recall(MemoryFilter.ByType(MemoryType.Custom("Report")), 10).map(_.map(_.id.value).toSet) shouldBe
        Right(Set("report", "legacy"))
      store.count(MemoryFilter.ByType(MemoryType.Task)) shouldBe Right(0L)
    }

  it should "replay an applied key's outcome while the embedding service is down" in skipIfDisabled {
    val key     = AppliedWriteKey("scope", "replayed")
    val memory  = Memory(MemoryId("replayed"), "replayed note", MemoryType.Knowledge)
    val applied = store.applyAtomically(key, 1L, Seq(memory), Seq.empty, MemoryBounds()).map(_._2)
    applied.isRight shouldBe true

    val down = new EmbeddingService {
      def dimensions                                = embeddingService.dimensions
      def embed(text: String): Result[Array[Float]] = Left(ProcessingError("embed-error", "down"))
      def embedBatch(texts: Seq[String])            = Left(ProcessingError("embed-error", "down"))
    }
    val retrying = PostgresMemoryStore(dbConfig, Some(down)).fold(e => fail(e.message), identity)
    retrying.applyAtomically(key, 1L, Seq(memory), Seq.empty, MemoryBounds()).map(_._2) shouldBe applied
    retrying
      .applyAtomically(AppliedWriteKey("scope", "new"), 2L, Seq(memory), Seq.empty, MemoryBounds())
      .isLeft shouldBe
      true
    retrying.close()
  }

  it should "not delete a row that a concurrent update makes stop matching" in skipIfDisabled {
    store.store(Memory(MemoryId("racy"), "drop me", MemoryType.Task)).isRight shouldBe true
    val filter = MemoryFilter.Custom(_.content.startsWith("drop"))

    scala.util.Using.resource(connection()) { other =>
      other.setAutoCommit(false)
      scala.util.Using.resource(other.createStatement())(
        _.executeUpdate(s"UPDATE $tableName SET content = 'keep me' WHERE id = 'racy'")
      )
      // The delete reads the row while the update holds it; it must wait for it and judge the new content
      val deleting = scala.concurrent.Future(store.deleteMatching(filter))(scala.concurrent.ExecutionContext.global)
      Thread.sleep(500)
      other.commit()
      scala.concurrent.Await.result(deleting, scala.concurrent.duration.Duration(30, "s")).isRight shouldBe true
    }
    store.get(MemoryId("racy")).map(_.map(_.content)) shouldBe Right(Some("keep me"))
  }

  it should "filter metadata an outside writer stored as numbers, booleans and nulls as matches reads it" in
    skipIfDisabled {
      // Not written by the store, which writes strings: JSON numbers, booleans and null, as another writer may
      def insert(id: String, metadata: String): Unit =
        execute(
          s"INSERT INTO $tableName (id, content, memory_type, metadata, created_at) " +
            s"VALUES ('$id', 'typed $id', 'task', '$metadata'::jsonb, now())"
        )
      insert("num", """{"count": 3, "ratio": 1.0, "entity_id": 7, "conversation_id": 9}""")
      insert("bool", """{"flag": true, "conversation_id": null}""")
      insert("nul", """{"count": null}""")
      insert("str", """{"count": "3", "flag": "false"}""")
      insert("none", """{}""")

      val all = store.recall(MemoryFilter.All, 10).fold(e => fail(e.message), identity)
      all.find(_.id.value == "num").map(_.metadata) shouldBe
        Some(Map("count" -> "3", "ratio" -> "1.0", "entity_id" -> "7", "conversation_id" -> "9"))

      val filters = Seq(
        MemoryFilter.ByMetadata("count", "3"),
        MemoryFilter.ByMetadata("ratio", "1.0"),
        MemoryFilter.ByMetadata("flag", "true"),
        MemoryFilter.ByMetadata("count", "null"),
        MemoryFilter.ByEntity(EntityId("7")),
        MemoryFilter.ByConversation("9"),
        MemoryFilter.ByConversation("null"),
        MemoryFilter.MetadataContains("ratio", "."),
        MemoryFilter.MetadataContains("flag", "ru"),
        MemoryFilter.HasMetadata("count"),
        MemoryFilter.HasMetadata("conversation_id")
      )
      (filters ++ filters.map(MemoryFilter.Not(_))).foreach { filter =>
        val expected = all.filter(filter.matches).map(_.id.value).toSet
        withClue(s"$filter: ") {
          store.recall(filter, 10).map(_.map(_.id.value).toSet) shouldBe Right(expected)
          store.count(filter) shouldBe Right(expected.size.toLong)
        }
      }
      store.recall(MemoryFilter.ByMetadata("count", "3"), 10).map(_.map(_.id.value).toSet) shouldBe
        Right(Set("num", "str"))

      // A delete through Not removes exactly what the predicate accepts
      store.deleteMatching(MemoryFilter.Not(MemoryFilter.ByMetadata("count", "3"))).isRight shouldBe true
      store.recall(MemoryFilter.All, 10).map(_.map(_.id.value).toSet) shouldBe Right(Set("num", "str"))
    }

  private def connection(): java.sql.Connection = {
    val ds = new org.postgresql.ds.PGSimpleDataSource()
    ds.setURL(dbConfig.jdbcUrl)
    ds.setUser(dbConfig.user)
    ds.setPassword(dbConfig.password.reveal)
    ds.getConnection
  }

  private def execute(sql: String): Unit = {
    scala.util.Using.resource(connection())(c => scala.util.Using.resource(c.createStatement())(_.execute(sql)))
    ()
  }

  it should "clamp similarity scores to [0, 1]" in skipIfDisabled {
    val clampEmbedding = embeddingService
      .embed("clamp example")
      .fold(
        e => fail(s"Test setup embedding failed: ${e.message}"),
        identity
      )

    val memory = Memory(
      MemoryId("clamp-test"),
      "clamp example",
      MemoryType.Task,
      embedding = Some(clampEmbedding)
    )
    store.store(memory).isRight shouldBe true

    store
      .search("clamp", 5, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results =>
          results match {
            case _ +: _ =>
              results.foreach { sm =>
                sm.score should be >= 0.0
                sm.score should be <= 1.0
              }
            case Nil =>
              fail("Expected non-empty results for clamp test")
          }
      )
  }

  it should "fail when embedding service returns empty vector" in skipIfDisabled {
    val emptyService = new EmbeddingService {
      def dimensions                                = 10
      def embed(text: String): Result[Array[Float]] = Right(Array.empty)
      def embedBatch(texts: Seq[String])            = Right(Seq.empty)
    }

    val storeWithEmpty = PostgresMemoryStore(dbConfig, Some(emptyService)).fold(e => fail(e.message), identity)

    storeWithEmpty
      .search("query", 5, MemoryFilter.All)
      .fold(
        err => err.message should include("vector is empty"),
        _ => fail("Expected search to fail due to empty embedding, but it succeeded")
      )
    storeWithEmpty.close()
  }

  it should "propagate embedding service failures" in skipIfDisabled {
    val failingService = new EmbeddingService {
      def dimensions                                = 10
      def embed(text: String): Result[Array[Float]] = Left(ProcessingError("embed-error", "boom"))
      def embedBatch(texts: Seq[String])            = Left(ProcessingError("embed-error", "boom"))
    }

    val storeFailing = PostgresMemoryStore(dbConfig, Some(failingService)).fold(e => fail(e.message), identity)

    storeFailing
      .search("query", 5, MemoryFilter.All)
      .fold(
        err => err.message should include("boom"),
        _ => fail("Expected search to fail due to embedding service error, but it succeeded")
      )
    storeFailing.close()
  }

  private def batch(n: Int): Seq[Memory] =
    (1 to n).map(i => Memory(MemoryId(s"batch-$i"), s"batch note $i", MemoryType.Knowledge))

  /** `m` with no content, which the table refuses; embedded already, so the store sends it as it is. */
  private def unwritable(m: Memory): Memory =
    m.copy(content = null).withEmbedding(Array.fill(embeddingService.dimensions)(0.1f))

  it should "store a batch with storeAll" in skipIfDisabled {
    store.storeAll(batch(5)).isRight shouldBe true
    store.count(MemoryFilter.All) shouldBe Right(5L)
  }

  it should "store nothing from a batch whose write fails part way through, and keep working" in skipIfDisabled {
    // content is NOT NULL, so the third row fails after two have been written in the same transaction.
    val bad = batch(5).updated(2, unwritable(batch(5)(2)))

    store.storeAll(bad).isLeft shouldBe true
    store.count(MemoryFilter.All) shouldBe Right(0L)

    store.storeAll(batch(3)).isRight shouldBe true
    store.count(MemoryFilter.All) shouldBe Right(3L)
  }

  it should "store a batch larger than one JDBC batch whole, in one transaction" in skipIfDisabled {
    val size = PostgresMemoryStore.WriteBatchSize * 2 + 7
    store.storeAll(batch(size)).isRight shouldBe true
    store.count(MemoryFilter.All) shouldBe Right(size.toLong)
    store.get(MemoryId(s"batch-$size")).map(_.map(_.content)) shouldBe Right(Some(s"batch note $size"))
  }

  it should "store nothing when a row of a later JDBC batch fails" in skipIfDisabled {
    val size = PostgresMemoryStore.WriteBatchSize + 3
    val bad  = batch(size).updated(size - 1, unwritable(batch(size)(size - 1)))

    store.storeAll(bad).fold(e => e.message should include("content"), _ => fail("expected the batch to fail"))
    store.count(MemoryFilter.All) shouldBe Right(0L)
  }

  it should "store a repeated id as its last occurrence and update an existing memory" in skipIfDisabled {
    store.store(Memory(MemoryId("batch-1"), "before", MemoryType.Knowledge)).isRight shouldBe true
    val repeated = batch(2) :+ Memory(MemoryId("batch-2"), "last", MemoryType.Knowledge)

    store.storeAll(repeated).isRight shouldBe true
    store.count(MemoryFilter.All) shouldBe Right(2L)
    store.get(MemoryId("batch-1")).map(_.map(_.content)) shouldBe Right(Some("batch note 1"))
    store.get(MemoryId("batch-2")).map(_.map(_.content)) shouldBe Right(Some("last"))
  }
}
