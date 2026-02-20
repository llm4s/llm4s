package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach
import scala.util.Try

/**
 * Tests for QdrantVectorStore.
 *
 * These tests require a running Qdrant instance.
 * Set environment variable QDRANT_TEST_URL to enable tests, e.g.:
 *   export QDRANT_TEST_URL="http://localhost:6333"
 *
 * To run Qdrant for testing:
 *   docker run -p 6333:6333 qdrant/qdrant
 */
class QdrantVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  // Skip tests if no Qdrant available
  private val testUrl   = sys.env.get("QDRANT_TEST_URL")
  private val skipTests = testUrl.isEmpty

  private var store: QdrantVectorStore = _
  private val testCollectionName       = s"test_vectors_${System.currentTimeMillis()}"

  override def beforeEach(): Unit =
    if (!skipTests) {
      store = QdrantVectorStore(
        testUrl.get,
        testCollectionName,
        sys.env.get("QDRANT_TEST_API_KEY")
      ).fold(
        e => fail(s"Failed to create store: ${e.formatted}"),
        identity
      )
    }

  override def afterEach(): Unit =
    if (store != null) {
      // Clean up test collection
      Try {
        store.clear()
      }
      store.close()
    }

  private def skipIfNoQdrant(test: => Unit): Unit =
    if (skipTests) {
      info("Skipping test - QDRANT_TEST_URL not set")
    } else {
      test
    }

  "QdrantVectorStore" should {

    "store and retrieve a single record" in skipIfNoQdrant {
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

    "return None for non-existent record" in skipIfNoQdrant {
      val result = store.get("non-existent")
      result shouldBe Right(None)
    }

    "upsert (replace) existing record" in skipIfNoQdrant {
      val record1 = VectorRecord("test-1", Array(0.1f, 0.2f), Some("Original"))
      val record2 = VectorRecord("test-1", Array(0.3f, 0.4f), Some("Updated"))

      store.upsert(record1) shouldBe Right(())
      store.upsert(record2) shouldBe Right(())

      val retrieved = store.get("test-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some(Some("Updated"))
    }

    "store multiple records in batch" in skipIfNoQdrant {
      val records = (1 to 10).map { i =>
        VectorRecord(s"batch-$i", Array(i.toFloat, (i * 2).toFloat), Some(s"Content $i"))
      }

      store.upsertBatch(records) shouldBe Right(())

      val count = store.count()
      count shouldBe Right(10L)
    }

    "delete a record" in skipIfNoQdrant {
      val record = VectorRecord("delete-me", Array(1.0f, 2.0f))
      store.upsert(record) shouldBe Right(())

      store.delete("delete-me") shouldBe Right(())

      store.get("delete-me") shouldBe Right(None)
    }

    "delete multiple records in batch" in skipIfNoQdrant {
      val records = (1 to 5).map(i => VectorRecord(s"del-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.deleteBatch(Seq("del-1", "del-2", "del-3")) shouldBe Right(())

      store.count() shouldBe Right(2L)
      store.get("del-4").toOption.flatten shouldBe defined
      store.get("del-5").toOption.flatten shouldBe defined
    }

    "clear all records" in skipIfNoQdrant {
      val records = (1 to 5).map(i => VectorRecord(s"clear-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.clear() shouldBe Right(())

      store.count() shouldBe Right(0L)
    }

    "search by vector similarity" in skipIfNoQdrant {
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

      results match {
        case Right(scored) =>
          scored.size shouldBe 3

          // Most similar should be "similar-1" (exact match)
          scored.headOption match {
            case Some(r) =>
              r.record.id shouldBe "similar-1"
              r.score should be > 0.9
            case None => fail("Expected at least one result but scored list was empty")
          }

          // Second should be "similar-2" (close)
          scored(1).record.id shouldBe "similar-2"

          // "similar-4" (opposite direction) should not be in top 3
          scored.map(_.record.id) should not contain "similar-4"

        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "filter records by metadata" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("doc-1", Array(1.0f), metadata = Map("type" -> "document", "lang" -> "en")),
        VectorRecord("doc-2", Array(2.0f), metadata = Map("type" -> "document", "lang" -> "es")),
        VectorRecord("code-1", Array(3.0f), metadata = Map("type" -> "code", "lang" -> "scala"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Filter by type
      val docFilter = MetadataFilter.Equals("type", "document")
      val docs      = store.list(filter = Some(docFilter))
      docs.fold(err => fail(s"Expected Right but got Left: ${err.formatted}"), d => d.size shouldBe 2)

      // Filter by language
      val enFilter = MetadataFilter.Equals("lang", "en")
      val enDocs   = store.list(filter = Some(enFilter))
      enDocs match {
        case Right(records) =>
          records.size shouldBe 1
          records.headOption match {
            case Some(r) => r.id shouldBe "doc-1"
            case None    => fail("Expected one record but list was empty")
          }
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "combine filters with AND/OR" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("a" -> "1", "b" -> "x")),
        VectorRecord("r2", Array(2.0f), metadata = Map("a" -> "1", "b" -> "y")),
        VectorRecord("r3", Array(3.0f), metadata = Map("a" -> "2", "b" -> "x"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // AND filter
      val andFilter = MetadataFilter.Equals("a", "1").and(MetadataFilter.Equals("b", "x"))
      val andResult = store.list(filter = Some(andFilter))
      andResult match {
        case Right(records) =>
          records.size shouldBe 1
          records.headOption match {
            case Some(r) => r.id shouldBe "r1"
            case None    => fail("Expected one record but list was empty")
          }
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }

      // OR filter
      val orFilter = MetadataFilter.Equals("a", "2").or(MetadataFilter.Equals("b", "y"))
      val orResult = store.list(filter = Some(orFilter))
      orResult.fold(err => fail(s"Expected Right but got Left: ${err.formatted}"), r => r.size shouldBe 2)
    }

    "return correct statistics" in skipIfNoQdrant {
      // Note: Qdrant requires same dimensions in same collection
      val records = Seq(
        VectorRecord("s1", Array(1.0f, 2.0f, 3.0f)),
        VectorRecord("s2", Array(4.0f, 5.0f, 6.0f)),
        VectorRecord("s3", Array(7.0f, 8.0f, 9.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val stats = store.stats()
      stats match {
        case Right(s) =>
          s.totalRecords shouldBe 3
          s.dimensions should contain(3)
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "paginate results with list" in skipIfNoQdrant {
      val records = (1 to 20).map(i => VectorRecord(f"page-$i%02d", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      val page1 = store.list(limit = 5, offset = 0)
      val page2 = store.list(limit = 5, offset = 5)

      // Ensure different pages have different records
      (page1, page2) match {
        case (Right(p1), Right(p2)) =>
          p1.size shouldBe 5
          p2.size shouldBe 5
          p1.map(_.id).toSet.intersect(p2.map(_.id).toSet) shouldBe empty
        case (Left(err), _) => fail(s"page1 failed: ${err.formatted}")
        case (_, Left(err)) => fail(s"page2 failed: ${err.formatted}")
      }
    }

    "search with metadata filter" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("f1", Array(1.0f, 0.0f), metadata = Map("cat" -> "a")),
        VectorRecord("f2", Array(0.9f, 0.1f), metadata = Map("cat" -> "b")),
        VectorRecord("f3", Array(0.8f, 0.2f), metadata = Map("cat" -> "a"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter  = Some(MetadataFilter.Equals("cat", "a"))
      val results = store.search(Array(1.0f, 0.0f), topK = 10, filter = filter)

      results match {
        case Right(scored) =>
          scored.size shouldBe 2
          scored.map(_.record.id).toSet shouldBe Set("f1", "f3")
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "retrieve multiple records by IDs with getBatch" in skipIfNoQdrant {
      val records = (1 to 5).map(i => VectorRecord(s"batch-$i", Array(i.toFloat, (i * 2).toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      val result = store.getBatch(Seq("batch-1", "batch-3", "batch-5"))
      result match {
        case Right(records) =>
          records.size shouldBe 3
          records.map(_.id).toSet shouldBe Set("batch-1", "batch-3", "batch-5")
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "return empty sequence for getBatch with empty IDs" in skipIfNoQdrant {
      store.getBatch(Seq.empty) shouldBe Right(Seq.empty)
    }

    "return empty sequence for getBatch with non-existent IDs" in skipIfNoQdrant {
      val result = store.getBatch(Seq("fake-1", "fake-2", "fake-3"))
      result.fold(err => fail(s"Expected Right but got Left: ${err.formatted}"), r => r shouldBe empty)
    }

    "getBatch should handle mix of existent and non-existent IDs" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("exists-1", Array(1.0f)),
        VectorRecord("exists-2", Array(2.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.getBatch(Seq("exists-1", "fake", "exists-2"))
      // Should return only the existing records
      result match {
        case Right(records) =>
          records.size shouldBe 2
          records.map(_.id).toSet shouldBe Set("exists-1", "exists-2")
        case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
      }
    }

    "delete records by ID prefix with deleteByPrefix" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("user:1", Array(1.0f)),
        VectorRecord("user:2", Array(2.0f)),
        VectorRecord("user:3", Array(3.0f)),
        VectorRecord("admin:1", Array(4.0f)),
        VectorRecord("admin:2", Array(5.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByPrefix("user:")
      deleted shouldBe Right(3L)

      store.count() shouldBe Right(2L)
      store.get("admin:1").toOption.flatten shouldBe defined
      store.get("admin:2").toOption.flatten shouldBe defined
      store.get("user:1").toOption.flatten shouldBe None
    }

    "deleteByPrefix should return 0 when no records match" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("doc-1", Array(1.0f)),
        VectorRecord("doc-2", Array(2.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByPrefix("nonexistent:")
      deleted shouldBe Right(0L)

      store.count() shouldBe Right(2L)
    }

    "deleteByPrefix should handle empty prefix" in skipIfNoQdrant {
      val records = (1 to 3).map(i => VectorRecord(s"id-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      // Empty prefix matches all records
      val deleted = store.deleteByPrefix("")
      deleted shouldBe Right(3L)

      store.count() shouldBe Right(0L)
    }

    "delete records matching metadata filter with deleteByFilter" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("d1", Array(1.0f), metadata = Map("status" -> "draft", "type" -> "post")),
        VectorRecord("d2", Array(2.0f), metadata = Map("status" -> "draft", "type" -> "post")),
        VectorRecord("d3", Array(3.0f), metadata = Map("status" -> "draft", "type" -> "page")),
        VectorRecord("p1", Array(4.0f), metadata = Map("status" -> "published", "type" -> "post")),
        VectorRecord("p2", Array(5.0f), metadata = Map("status" -> "published", "type" -> "post"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Delete all draft posts
      val filter  = MetadataFilter.Equals("status", "draft").and(MetadataFilter.Equals("type", "post"))
      val deleted = store.deleteByFilter(filter)
      deleted shouldBe Right(2L)

      store.count() shouldBe Right(3L)
      // Should keep draft page and published posts
      store.get("d3").toOption.flatten shouldBe defined
      store.get("p1").toOption.flatten shouldBe defined
      store.get("p2").toOption.flatten shouldBe defined
    }

    "deleteByFilter should return 0 when no records match filter" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("color" -> "red")),
        VectorRecord("r2", Array(2.0f), metadata = Map("color" -> "blue"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter  = MetadataFilter.Equals("color", "green")
      val deleted = store.deleteByFilter(filter)
      deleted shouldBe Right(0L)

      store.count() shouldBe Right(2L)
    }

    "deleteByFilter should work with complex filters" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("priority" -> "high", "done" -> "false")),
        VectorRecord("r2", Array(2.0f), metadata = Map("priority" -> "high", "done" -> "true")),
        VectorRecord("r3", Array(3.0f), metadata = Map("priority" -> "low", "done" -> "false")),
        VectorRecord("r4", Array(4.0f), metadata = Map("priority" -> "low", "done" -> "true"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Delete all completed items (done=true) OR low priority items
      val filter  = MetadataFilter.Equals("done", "true").or(MetadataFilter.Equals("priority", "low"))
      val deleted = store.deleteByFilter(filter)

      // Should delete r2 (high+done), r3 (low+not done), r4 (low+done)
      deleted.fold(err => fail(s"deleteByFilter failed: ${err.formatted}"), d => d should be >= 3L)
      store.count().fold(err => fail(s"count failed: ${err.formatted}"), c => c should be <= 1L)
    }

    "count records with metadata filter" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("c1", Array(1.0f), metadata = Map("active" -> "true", "category" -> "A")),
        VectorRecord("c2", Array(2.0f), metadata = Map("active" -> "true", "category" -> "B")),
        VectorRecord("c3", Array(3.0f), metadata = Map("active" -> "false", "category" -> "A")),
        VectorRecord("c4", Array(4.0f), metadata = Map("active" -> "false", "category" -> "B"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Count active records
      val activeFilter = MetadataFilter.Equals("active", "true")
      store.count(Some(activeFilter)) shouldBe Right(2L)

      // Count category A records
      val categoryFilter = MetadataFilter.Equals("category", "A")
      store.count(Some(categoryFilter)) shouldBe Right(2L)

      // Count active category A records
      val combinedFilter = activeFilter.and(categoryFilter)
      store.count(Some(combinedFilter)) shouldBe Right(1L)

      // Count all records
      store.count(None) shouldBe Right(4L)
    }

    "count should return 0 when no records match filter" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("tag" -> "alpha")),
        VectorRecord("r2", Array(2.0f), metadata = Map("tag" -> "beta"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter = MetadataFilter.Equals("tag", "gamma")
      store.count(Some(filter)) shouldBe Right(0L)
    }

    "count should work with OR filters" in skipIfNoQdrant {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("env" -> "dev")),
        VectorRecord("r2", Array(2.0f), metadata = Map("env" -> "staging")),
        VectorRecord("r3", Array(3.0f), metadata = Map("env" -> "prod")),
        VectorRecord("r4", Array(4.0f), metadata = Map("env" -> "dev"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Count dev OR staging environments
      val filter = MetadataFilter.Equals("env", "dev").or(MetadataFilter.Equals("env", "staging"))
      store.count(Some(filter)) shouldBe Right(3L)
    }
  }

  "QdrantVectorStore factory" should {

    "create store from config" in skipIfNoQdrant {
      val config = QdrantVectorStore.Config(
        host = "localhost",
        port = 6333,
        collectionName = s"test_factory_${System.currentTimeMillis()}"
      )

      val result = QdrantVectorStore(config)
      result.fold(
        err => fail(s"Expected Right but got Left: ${err.formatted}"),
        store => store.close()
      )
    }
  }
}
