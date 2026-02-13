package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach

class VectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  var store: VectorStore = _

  override def beforeEach(): Unit =
    store = SQLiteVectorStore
      .inMemory()
      .fold(
        e => fail(s"Failed to create store: ${e.formatted}"),
        identity
      )

  override def afterEach(): Unit =
    if (store != null) store.close()

  "VectorStore" should {

    "store and retrieve a single record" in {
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

    "return None for non-existent record" in {
      val result = store.get("non-existent")
      result shouldBe Right(None)
    }

    "upsert (replace) existing record" in {
      val record1 = VectorRecord("test-1", Array(0.1f, 0.2f), Some("Original"))
      val record2 = VectorRecord("test-1", Array(0.3f, 0.4f), Some("Updated"))

      store.upsert(record1) shouldBe Right(())
      store.upsert(record2) shouldBe Right(())

      val retrieved = store.get("test-1")
      retrieved.toOption.flatten.map(_.content) shouldBe Some(Some("Updated"))
    }

    "store multiple records in batch" in {
      val records = (1 to 10).map { i =>
        VectorRecord(s"batch-$i", Array(i.toFloat, (i * 2).toFloat), Some(s"Content $i"))
      }

      store.upsertBatch(records) shouldBe Right(())

      val count = store.count()
      count shouldBe Right(10L)
    }

    "delete a record" in {
      val record = VectorRecord("delete-me", Array(1.0f, 2.0f))
      store.upsert(record) shouldBe Right(())

      store.delete("delete-me") shouldBe Right(())

      store.get("delete-me") shouldBe Right(None)
    }

    "delete multiple records in batch" in {
      val records = (1 to 5).map(i => VectorRecord(s"del-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.deleteBatch(Seq("del-1", "del-2", "del-3")) shouldBe Right(())

      store.count() shouldBe Right(2L)
      store.get("del-4").toOption.flatten shouldBe defined
      store.get("del-5").toOption.flatten shouldBe defined
    }

    "clear all records" in {
      val records = (1 to 5).map(i => VectorRecord(s"clear-$i", Array(i.toFloat)))
      store.upsertBatch(records) shouldBe Right(())

      store.clear() shouldBe Right(())

      store.count() shouldBe Right(0L)
    }

    "search by vector similarity" in {
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

    "filter records by metadata" in {
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

    "combine filters with AND/OR" in {
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

    "return correct statistics" in {
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

    "paginate results with list" in {
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

    "search with metadata filter" in {
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

    "fail-fast on dimension mismatch during search" in {
      // Upsert a record with 2 dimensions
      val record = VectorRecord("dim-test", Array(1.0f, 1.0f))
      store.upsert(record) shouldBe Right(())

      // Attempt search with a different dimension (3 dimensions)
      val queryVector = Array(1.0f, 2.0f, 3.0f)
      val result      = store.search(queryVector, topK = 5)

      // Should return Left with error
      result.isLeft shouldBe true
      val error = result.left.toOption.get
      error.formatted should include("Dimension mismatch")
      error.formatted should include("query vector has 3 dimensions")
      error.formatted should include("stored vectors have 2 dimensions")
    }

    "return empty sequence when searching an empty store" in {
      // Verify the store is empty
      store.count() shouldBe Right(0L)

      // Search on empty store should succeed and return empty sequence
      val queryVector = Array(1.0f, 2.0f, 3.0f)
      val result      = store.search(queryVector, topK = 5)

      result shouldBe Right(Seq.empty)
    }

    "successfully search with matching dimensions" in {
      // Upsert records with 2 dimensions
      val records = Seq(
        VectorRecord("match-1", Array(1.0f, 0.0f), Some("First")),
        VectorRecord("match-2", Array(0.9f, 0.1f), Some("Second")),
        VectorRecord("match-3", Array(0.0f, 1.0f), Some("Third"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Search with matching 2-dimension query vector
      val queryVector = Array(1.0f, 0.0f)
      val result      = store.search(queryVector, topK = 2)

      // Should succeed and return results
      result.isRight shouldBe true
      val scored = result.toOption.get
      scored.size shouldBe 2
      scored.head.record.id shouldBe "match-1" // Exact match should be first
      scored.head.score should be > 0.9
    }
  }

  "VectorStoreFactory" should {

    "create in-memory SQLite store" in {
      val result = VectorStoreFactory.inMemory()
      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "create store from config" in {
      val config = VectorStoreFactory.Config.inMemory
      val result = VectorStoreFactory.create(config)
      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "create store from provider name" in {
      val result = VectorStoreFactory.create("sqlite")
      result.isRight shouldBe true
      result.toOption.get.close()
    }

    "reject unknown provider" in {
      val result = VectorStoreFactory.create("unknown-provider")
      result.isLeft shouldBe true
    }
  }

  "VectorRecord" should {

    "create with auto-generated ID" in {
      val record = VectorRecord.create(Array(1.0f, 2.0f))
      record.id should not be empty
      record.dimensions shouldBe 2
    }

    "support metadata operations" in {
      val record = VectorRecord("test", Array(1.0f))
        .withMetadata("key1", "value1")
        .withMetadata(Map("key2" -> "value2", "key3" -> "value3"))

      record.getMetadata("key1") shouldBe Some("value1")
      record.getMetadata("key2") shouldBe Some("value2")
      record.getMetadata("key3") shouldBe Some("value3")
      record.getMetadata("missing") shouldBe None
    }
  }

  "MetadataFilter" should {

    "support DSL syntax" in {
      import MetadataFilter._

      val filter1 = Equals("a", "1").and(Equals("b", "2"))
      filter1 shouldBe a[And]

      val filter2 = Equals("a", "1").or(Equals("b", "2"))
      filter2 shouldBe a[Or]

      val filter3 = !Equals("a", "1")
      filter3 shouldBe a[Not]
    }
  }
}
