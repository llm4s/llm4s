package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach
import scala.util.Try

/**
 * Tests for PgVectorStore.
 *
 * These tests require a running PostgreSQL database with pgvector extension.
 * Set environment variable PGVECTOR_TEST_URL to enable tests, e.g.:
 *   export PGVECTOR_TEST_URL="jdbc:postgresql://localhost:5432/postgres"
 *
 * To set up PostgreSQL for testing:
 *   1. Start PostgreSQL (e.g., Postgres.app or docker)
 *   2. Run: psql -c "CREATE EXTENSION IF NOT EXISTS vector;"
 */
class PgVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  // Skip tests if no PostgreSQL available
  private val testUrl   = sys.env.get("PGVECTOR_TEST_URL")
  private val skipTests = testUrl.isEmpty

  private var store: PgVectorStore = _
  private val testTableName        = s"test_vectors_${System.currentTimeMillis()}"

  override def beforeEach(): Unit =
    if (!skipTests) {
      store = PgVectorStore(
        testUrl.get,
        sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres"),
        sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""),
        testTableName
      ).fold(
        e => fail(s"Failed to create store: ${e.formatted}"),
        identity
      )
    }

  override def afterEach(): Unit =
    if (store != null) {
      // Clean up test table
      Try {
        store.clear()
      }
      store.close()
    }

  private def skipIfNoPg(test: => Unit): Unit =
    if (skipTests) {
      info("Skipping test - PGVECTOR_TEST_URL not set")
    } else {
      test
    }

  "PgVectorStore" should {

    "store and retrieve a single record" in skipIfNoPg {
      val record = VectorRecord(
        id = "test-1",
        embedding = Array(0.1f, 0.2f, 0.3f),
        content = Some("Test content"),
        metadata = Map("source" -> "test", "type" -> "document")
      )

      store.upsert(record) shouldBe Right(())

      val retrieved = store.get("test-1")
      retrieved.isRight shouldBe true
      retrieved.toOption.flatten.map(_.id) shouldBe Some("test-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some(Some("Test content"))
      retrieved.toOption.flatten.map(_.metadata) shouldBe Some(Map("source" -> "test", "type" -> "document"))
    }

    "return None for non-existent record" in skipIfNoPg {
      val result = store.get("non-existent")
      result shouldBe Right(None)
    }

    "upsert (replace) existing record" in skipIfNoPg {
      val record1 = VectorRecord("test-1", Array(0.1f, 0.2f), Some("Original"))
      val record2 = VectorRecord("test-1", Array(0.3f, 0.4f), Some("Updated"))

      store.upsert(record1) shouldBe Right(())
      store.upsert(record2) shouldBe Right(())

      val retrieved = store.get("test-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some(Some("Updated"))
    }

    "store multiple records in batch" in skipIfNoPg {
      val records = (1 to 10).map { i =>
        VectorRecord(s"batch-$i", Array(i.toFloat, (i * 2).toFloat), Some(s"Content $i"))
      }

      store.upsertBatch(records) shouldBe Right(())

      val count = store.count()
      count shouldBe Right(10L)
    }

    "delete a record" in skipIfNoPg {
      val record = VectorRecord("delete-me", Array(1.0f, 2.0f))
      store.upsert(record) shouldBe Right(())

      store.delete("delete-me") shouldBe Right(())

      store.get("delete-me") shouldBe Right(None)
    }

    "delete multiple records in batch" in skipIfNoPg {
      val records = (1 to 5).map(i => VectorRecord(s"del-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.deleteBatch(Seq("del-1", "del-2", "del-3")) shouldBe Right(())

      store.count() shouldBe Right(2L)
      store.get("del-4").toOption.flatten shouldBe defined
      store.get("del-5").toOption.flatten shouldBe defined
    }

    "clear all records" in skipIfNoPg {
      val records = (1 to 5).map(i => VectorRecord(s"clear-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.clear() shouldBe Right(())

      store.count() shouldBe Right(0L)
    }

    "search by vector similarity" in skipIfNoPg {
      // Create records with known embeddings
      val records = Seq(
        VectorRecord("similar-1", Array(1.0f, 0.0f, 0.0f), Some("Pointing right")),
        VectorRecord("similar-2", Array(0.9f, 0.1f, 0.0f), Some("Almost right")),
        VectorRecord("similar-3", Array(0.0f, 1.0f, 0.0f), Some("Pointing up")),
        VectorRecord("similar-4", Array(-1.0f, 0.0f, 0.0f), Some("Pointing left"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Search for vectors similar to "pointing right"
      val queryVector = Array(1.0f, 0.0f, 0.0f)
      val results     = store.search(queryVector, topK = 3)

      results.isRight shouldBe true
      val scored = results.toOption.get
      scored.size shouldBe 3

      // Most similar should be "similar-1" (exact match)
      scored.head.record.id shouldBe "similar-1"
      scored.head.score should be > 0.9

      // Second should be "similar-2" (close)
      scored(1).record.id shouldBe "similar-2"

      // "similar-4" (opposite direction) should not be in top 3
      scored.map(_.record.id) should not contain "similar-4"
    }

    "filter records by metadata" in skipIfNoPg {
      val records = Seq(
        VectorRecord("doc-1", Array(1.0f), metadata = Map("type" -> "document", "lang" -> "en")),
        VectorRecord("doc-2", Array(2.0f), metadata = Map("type" -> "document", "lang" -> "es")),
        VectorRecord("code-1", Array(3.0f), metadata = Map("type" -> "code", "lang" -> "scala"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Filter by type
      val docFilter = MetadataFilter.Equals("type", "document")
      val docs      = store.list(filter = Some(docFilter))
      docs.toOption.get.size shouldBe 2

      // Filter by language
      val enFilter = MetadataFilter.Equals("lang", "en")
      val enDocs   = store.list(filter = Some(enFilter))
      enDocs.toOption.get.size shouldBe 1
      enDocs.toOption.get.head.id shouldBe "doc-1"
    }

    "combine filters with AND/OR" in skipIfNoPg {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("a" -> "1", "b" -> "x")),
        VectorRecord("r2", Array(2.0f), metadata = Map("a" -> "1", "b" -> "y")),
        VectorRecord("r3", Array(3.0f), metadata = Map("a" -> "2", "b" -> "x"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // AND filter
      val andFilter = MetadataFilter.Equals("a", "1").and(MetadataFilter.Equals("b", "x"))
      val andResult = store.list(filter = Some(andFilter))
      andResult.toOption.get.size shouldBe 1
      andResult.toOption.get.head.id shouldBe "r1"

      // OR filter
      val orFilter = MetadataFilter.Equals("a", "2").or(MetadataFilter.Equals("b", "y"))
      val orResult = store.list(filter = Some(orFilter))
      orResult.toOption.get.size shouldBe 2
    }

    "return correct statistics" in skipIfNoPg {
      val records = Seq(
        VectorRecord("s1", Array(1.0f, 2.0f, 3.0f)),
        VectorRecord("s2", Array(4.0f, 5.0f, 6.0f)),
        VectorRecord("s3", Array(1.0f, 2.0f)) // Different dimension
      )
      store.upsertBatch(records) shouldBe Right(())

      val stats = store.stats()
      stats.isRight shouldBe true
      stats.toOption.get.totalRecords shouldBe 3
      stats.toOption.get.dimensions should contain(3)
      stats.toOption.get.dimensions should contain(2)
    }

    "paginate results with list" in skipIfNoPg {
      val records = (1 to 20).map(i => VectorRecord(f"page-$i%02d", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      val page1 = store.list(limit = 5, offset = 0)
      page1.toOption.get.size shouldBe 5

      val page2 = store.list(limit = 5, offset = 5)
      page2.toOption.get.size shouldBe 5

      // Ensure different pages have different records
      val ids1 = page1.toOption.get.map(_.id).toSet
      val ids2 = page2.toOption.get.map(_.id).toSet
      (ids1.intersect(ids2)) shouldBe empty
    }

    "search with metadata filter" in skipIfNoPg {
      val records = Seq(
        VectorRecord("f1", Array(1.0f, 0.0f), metadata = Map("cat" -> "a")),
        VectorRecord("f2", Array(0.9f, 0.1f), metadata = Map("cat" -> "b")),
        VectorRecord("f3", Array(0.8f, 0.2f), metadata = Map("cat" -> "a"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter  = Some(MetadataFilter.Equals("cat", "a"))
      val results = store.search(Array(1.0f, 0.0f), topK = 10, filter = filter)

      results.isRight shouldBe true
      results.toOption.get.size shouldBe 2
      results.toOption.get.map(_.record.id).toSet shouldBe Set("f1", "f3")
    }
  }

  "PgVectorStore factory" should {

    "create store from config" in skipIfNoPg {
      // Parse connection details from JDBC URL
      // Format: jdbc:postgresql://host:port/database
      val urlPattern = """jdbc:postgresql://([^:]+):(\d+)/(.+)""".r
      val (host, port, database) = testUrl.get match {
        case urlPattern(h, p, d) => (h, p.toInt, d)
        case _                   => ("localhost", 5432, "postgres")
      }

      val config = PgVectorStore.Config(
        host = host,
        port = port,
        database = database,
        user = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres"),
        password = sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", ""),
        tableName = s"test_factory_${System.currentTimeMillis()}"
      )

      val result = PgVectorStore(config)
      result.isRight shouldBe true
      result.toOption.get.close()
    }
  }
}
