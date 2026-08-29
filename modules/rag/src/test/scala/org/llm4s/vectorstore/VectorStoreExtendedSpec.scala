package org.llm4s.vectorstore

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterEach

/**
 * Extended tests for VectorStore covering edge cases and additional operations.
 */
class VectorStoreExtendedSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

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

  // ==========================================================================
  // DeleteByPrefix Tests
  // ==========================================================================

  "VectorStore.deleteByPrefix" should {

    "delete records matching prefix" in {
      val records = Seq(
        VectorRecord("user/doc-1", Array(1.0f)),
        VectorRecord("user/doc-2", Array(2.0f)),
        VectorRecord("user/doc-3", Array(3.0f)),
        VectorRecord("admin/doc-1", Array(4.0f)),
        VectorRecord("admin/doc-2", Array(5.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByPrefix("user/")

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 3L

      store.count() shouldBe Right(2L)
      store.get("admin/doc-1").toOption.flatten shouldBe defined
    }

    "return 0 when no records match prefix" in {
      val records = Seq(
        VectorRecord("doc-1", Array(1.0f)),
        VectorRecord("doc-2", Array(2.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByPrefix("nonexistent/")

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 0L
      store.count() shouldBe Right(2L)
    }

    "handle empty store" in {
      val deleted = store.deleteByPrefix("any/")

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 0L
    }

    "handle empty prefix (delete all)" in {
      val records = Seq(
        VectorRecord("a", Array(1.0f)),
        VectorRecord("b", Array(2.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByPrefix("")

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 2L
      store.count() shouldBe Right(0L)
    }
  }

  // ==========================================================================
  // DeleteByFilter Tests
  // ==========================================================================

  "VectorStore.deleteByFilter" should {

    "delete records matching Equals filter" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("status" -> "active")),
        VectorRecord("r2", Array(2.0f), metadata = Map("status" -> "inactive")),
        VectorRecord("r3", Array(3.0f), metadata = Map("status" -> "active"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByFilter(MetadataFilter.Equals("status", "inactive"))

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 1L
      store.count() shouldBe Right(2L)
    }

    "delete records matching And filter" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("type" -> "doc", "lang" -> "en")),
        VectorRecord("r2", Array(2.0f), metadata = Map("type" -> "doc", "lang" -> "es")),
        VectorRecord("r3", Array(3.0f), metadata = Map("type" -> "code", "lang" -> "en"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter  = MetadataFilter.Equals("type", "doc").and(MetadataFilter.Equals("lang", "en"))
      val deleted = store.deleteByFilter(filter)

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 1L
      store.get("r1") shouldBe Right(None)
    }

    "delete records matching Or filter" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("priority" -> "high")),
        VectorRecord("r2", Array(2.0f), metadata = Map("priority" -> "medium")),
        VectorRecord("r3", Array(3.0f), metadata = Map("priority" -> "low"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val filter  = MetadataFilter.Equals("priority", "high").or(MetadataFilter.Equals("priority", "low"))
      val deleted = store.deleteByFilter(filter)

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 2L
      store.count() shouldBe Right(1L)
    }

    "return 0 when no records match" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("x" -> "1"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val deleted = store.deleteByFilter(MetadataFilter.Equals("y", "2"))

      deleted.isRight shouldBe true
      deleted.toOption.get shouldBe 0L
    }
  }

  // ==========================================================================
  // GetBatch Tests
  // ==========================================================================

  "VectorStore.getBatch" should {

    "retrieve multiple records by IDs" in {
      val records = Seq(
        VectorRecord("batch-1", Array(1.0f), Some("Content 1")),
        VectorRecord("batch-2", Array(2.0f), Some("Content 2")),
        VectorRecord("batch-3", Array(3.0f), Some("Content 3"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.getBatch(Seq("batch-1", "batch-3"))

      result.isRight shouldBe true
      val retrieved = result.toOption.get
      retrieved.size shouldBe 2
      retrieved.map(_.id).toSet shouldBe Set("batch-1", "batch-3")
    }

    "skip non-existent IDs" in {
      val records = Seq(
        VectorRecord("exists-1", Array(1.0f)),
        VectorRecord("exists-2", Array(2.0f))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.getBatch(Seq("exists-1", "missing", "exists-2"))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 2
    }

    "return empty for all non-existent IDs" in {
      val result = store.getBatch(Seq("missing-1", "missing-2"))

      result.isRight shouldBe true
      result.toOption.get shouldBe empty
    }

    "handle empty ID list" in {
      val result = store.getBatch(Seq.empty)

      result.isRight shouldBe true
      result.toOption.get shouldBe empty
    }
  }

  // ==========================================================================
  // Additional Filter Tests
  // ==========================================================================

  "MetadataFilter.Contains" should {

    "match records containing substring" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("desc" -> "hello world")),
        VectorRecord("r2", Array(2.0f), metadata = Map("desc" -> "goodbye world")),
        VectorRecord("r3", Array(3.0f), metadata = Map("desc" -> "hello there"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.list(filter = Some(MetadataFilter.Contains("desc", "hello")))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 2
      result.toOption.get.map(_.id).toSet shouldBe Set("r1", "r3")
    }
  }

  "MetadataFilter.HasKey" should {

    "match records that have the specified key" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("author" -> "alice")),
        VectorRecord("r2", Array(2.0f), metadata = Map("editor" -> "bob")),
        VectorRecord("r3", Array(3.0f), metadata = Map("author" -> "charlie", "editor" -> "dave"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.list(filter = Some(MetadataFilter.HasKey("author")))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 2
      result.toOption.get.map(_.id).toSet shouldBe Set("r1", "r3")
    }
  }

  "MetadataFilter.In" should {

    "be expressible using Or filter as fallback" in {
      // Note: MetadataFilter.In may not be directly supported by all backends
      // This test uses OR as an equivalent approach
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("status" -> "draft")),
        VectorRecord("r2", Array(2.0f), metadata = Map("status" -> "review")),
        VectorRecord("r3", Array(3.0f), metadata = Map("status" -> "published")),
        VectorRecord("r4", Array(4.0f), metadata = Map("status" -> "archived"))
      )
      store.upsertBatch(records) shouldBe Right(())

      // Using Or to simulate In behavior
      val filter = MetadataFilter.Equals("status", "draft").or(MetadataFilter.Equals("status", "review"))
      val result = store.list(filter = Some(filter))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 2
      result.toOption.get.map(_.id).toSet shouldBe Set("r1", "r2")
    }
  }

  "MetadataFilter.Not" should {

    "negate a filter condition" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("visible" -> "true")),
        VectorRecord("r2", Array(2.0f), metadata = Map("visible" -> "false")),
        VectorRecord("r3", Array(3.0f), metadata = Map("visible" -> "true"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.list(filter = Some(MetadataFilter.Not(MetadataFilter.Equals("visible", "true"))))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 1
      result.toOption.get.head.id shouldBe "r2"
    }
  }

  "MetadataFilter.All" should {

    "match all records" in {
      val records = Seq(
        VectorRecord("r1", Array(1.0f), metadata = Map("a" -> "1")),
        VectorRecord("r2", Array(2.0f), metadata = Map("b" -> "2"))
      )
      store.upsertBatch(records) shouldBe Right(())

      val result = store.list(filter = Some(MetadataFilter.All))

      result.isRight shouldBe true
      result.toOption.get.size shouldBe 2
    }
  }

  // ==========================================================================
  // VectorRecord Equality Tests
  // ==========================================================================

  "VectorRecord" should {

    "consider two records equal if same ID and embedding" in {
      val r1 = VectorRecord("test", Array(1.0f, 2.0f, 3.0f))
      val r2 = VectorRecord("test", Array(1.0f, 2.0f, 3.0f))

      r1 shouldBe r2
      r1.hashCode shouldBe r2.hashCode
    }

    "consider records not equal with different embeddings" in {
      val r1 = VectorRecord("test", Array(1.0f, 2.0f, 3.0f))
      val r2 = VectorRecord("test", Array(1.0f, 2.0f, 4.0f))

      r1 should not be r2
    }

    "consider records not equal with different IDs" in {
      val r1 = VectorRecord("test1", Array(1.0f, 2.0f))
      val r2 = VectorRecord("test2", Array(1.0f, 2.0f))

      r1 should not be r2
    }

    "report correct dimensions" in {
      val r1 = VectorRecord("test", Array(1.0f, 2.0f, 3.0f))
      val r2 = VectorRecord("test", Array(1.0f))

      r1.dimensions shouldBe 3
      r2.dimensions shouldBe 1
    }
  }

  // ==========================================================================
  // ScoredRecord Tests
  // ==========================================================================

  "ScoredRecord" should {

    "validate score is between 0 and 1" in {
      val record = VectorRecord("test", Array(1.0f))

      noException should be thrownBy ScoredRecord(record, 0.0)
      noException should be thrownBy ScoredRecord(record, 0.5)
      noException should be thrownBy ScoredRecord(record, 1.0)
    }

    "order by score descending" in {
      val r1 = ScoredRecord(VectorRecord("a", Array(1.0f)), 0.3)
      val r2 = ScoredRecord(VectorRecord("b", Array(1.0f)), 0.9)
      val r3 = ScoredRecord(VectorRecord("c", Array(1.0f)), 0.5)

      val sorted = Seq(r1, r2, r3).sorted

      sorted.map(_.record.id) shouldBe Seq("b", "c", "a")
    }
  }

  // ==========================================================================
  // VectorStoreStats Tests
  // ==========================================================================

  "VectorStoreStats" should {

    "report isEmpty correctly" in {
      val empty    = VectorStoreStats(0, Set.empty, None)
      val notEmpty = VectorStoreStats(5, Set(3), None)

      empty.isEmpty shouldBe true
      notEmpty.isEmpty shouldBe false
    }

    "format size correctly" in {
      val stats1 = VectorStoreStats(10, Set(3), Some(1024L))
      val stats2 = VectorStoreStats(10, Set(3), Some(1024L * 1024L))
      val stats3 = VectorStoreStats(10, Set(3), None)

      stats1.formattedSize should include("KB")
      stats2.formattedSize should include("MB")
      stats3.formattedSize shouldBe "unknown"
    }
  }

  // ==========================================================================
  // Edge Cases
  // ==========================================================================

  "VectorStore" should {

    "handle records with empty content" in {
      val record = VectorRecord("empty-content", Array(1.0f), content = None)
      store.upsert(record) shouldBe Right(())

      val retrieved = store.get("empty-content")
      retrieved.toOption.flatten.map(_.content) shouldBe Some(None)
    }

    "handle records with empty metadata" in {
      val record = VectorRecord("empty-meta", Array(1.0f), metadata = Map.empty)
      store.upsert(record) shouldBe Right(())

      val retrieved = store.get("empty-meta")
      retrieved.toOption.flatten.map(_.metadata) shouldBe Some(Map.empty)
    }

    "handle special characters in metadata" in {
      val record = VectorRecord(
        "special",
        Array(1.0f),
        metadata = Map(
          "unicode" -> "日本語",
          "simple"  -> "hello world"
        )
      )
      store.upsert(record) shouldBe Right(())

      val retrieved = store.get("special")
      retrieved.isRight shouldBe true
      val meta = retrieved.toOption.flatten.get.metadata
      meta("unicode") shouldBe "日本語"
      meta("simple") shouldBe "hello world"
    }

    "handle single-element embedding" in {
      val record = VectorRecord("single", Array(0.5f))
      store.upsert(record) shouldBe Right(())

      val results = store.search(Array(0.5f), topK = 1)
      results.isRight shouldBe true
      results.toOption.get.head.record.id shouldBe "single"
    }

    "handle large batch operations" in {
      val records = (1 to 100).map(i => VectorRecord(s"large-$i", Array(i.toFloat / 100f)))
      store.upsertBatch(records) shouldBe Right(())

      store.count() shouldBe Right(100L)

      val ids = (1 to 50).map(i => s"large-$i")
      store.deleteBatch(ids) shouldBe Right(())

      store.count() shouldBe Right(50L)
    }
  }
}
