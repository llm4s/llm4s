package org.llm4s.vectorstore

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
 *
 * To run locally:
 *   sbt "it/testOnly org.llm4s.vectorstore.QdrantVectorStoreSpec"
 */
class QdrantVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

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
      Try {
        store.clear()
      }
      store.close()
    }

  private def skipIfNoQdrant(test: => Unit): Unit =
    if (skipTests) info("Skipping test - QDRANT_TEST_URL not set")
    else test

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
  }
}
