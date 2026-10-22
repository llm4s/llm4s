package org.llm4s.vectorstore.opensearch

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

/**
 * [[OpenSearchVectorStore]] against a real OpenSearch with the k-NN plugin (#1469).
 *
 * Set `OPENSEARCH_TEST_URL` to enable it; without it every test is cancelled. A single node with the security plugin
 * disabled is enough:
 * {{{
 * docker run -p 9201:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true \
 *   -e DISABLE_INSTALL_DEMO_CONFIG=true opensearchproject/opensearch:2.19.1
 * export OPENSEARCH_TEST_URL=http://localhost:9201
 * sbt "it/testOnly org.llm4s.vectorstore.opensearch.OpenSearchVectorStoreSpec"
 * }}}
 * For a secured cluster set `OPENSEARCH_TEST_USERNAME` and `OPENSEARCH_TEST_PASSWORD` as well.
 */
@Docker
class OpenSearchVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  private val testUrl  = sys.env.get("OPENSEARCH_TEST_URL").filter(_.nonEmpty)
  private val username = sys.env.get("OPENSEARCH_TEST_USERNAME").filter(_.nonEmpty)
  private val password = sys.env.get("OPENSEARCH_TEST_PASSWORD").getOrElse("")

  private val opened = mutable.Buffer.empty[OpenSearchVectorStore]

  override def afterEach(): Unit = {
    opened.foreach { s =>
      s.clear()
      s.close()
    }
    opened.clear()
  }

  /** A store over a fresh index, on `engine` and `spaceType`. */
  private def newStore(
    engine: OpenSearchEngine = OpenSearchEngine.Lucene,
    spaceType: OpenSearchSpaceType = OpenSearchSpaceType.CosineSimil
  ): OpenSearchVectorStore = {
    val config = OpenSearchConfig(
      baseUrl = testUrl.getOrElse(fail("OPENSEARCH_TEST_URL not set")),
      index = s"llm4s-it-${engine.wireName}-${System.nanoTime()}",
      auth = username.fold[OpenSearchAuth](OpenSearchAuth.NoAuth)(OpenSearchAuth.Basic(_, password)),
      spaceType = spaceType,
      engine = engine
    )
    val store = OpenSearchVectorStore(config).fold(e => fail(s"Failed to create store: ${e.formatted}"), identity)
    opened += store
    store
  }

  private def withOpenSearch(test: => Unit): Unit = {
    Tier.require(testUrl.isDefined, "OPENSEARCH_TEST_URL not set")
    test
  }

  private def rec(id: String, v: Array[Float], meta: (String, String)*): VectorRecord =
    VectorRecord(id, v, Some(s"content of $id"), meta.toMap)

  private val records = Seq(
    rec("doc-1", Array(1f, 0f, 0f), "lang"     -> "en", "path" -> "/guides/scala.md"),
    rec("doc-2", Array(0.6f, 0.8f, 0f), "lang" -> "en", "path" -> "/guides/java.md", "draft" -> "yes"),
    rec("doc-3", Array(0f, 1f, 0f), "lang"     -> "fr", "path" -> "/notes/a.txt"),
    rec("other-1", Array(0f, 0f, 1f), "lang"   -> "de")
  )

  "OpenSearchVectorStore" should {

    "store and read back a record" in withOpenSearch {
      val store = newStore()
      store.upsert(records.head) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(records.head))
      store.get("missing") shouldBe Right(None)
    }

    "answer as an empty store before anything is written" in withOpenSearch {
      val store = newStore()
      store.count() shouldBe Right(0L)
      store.search(Array(1f, 0f, 0f), topK = 3) shouldBe Right(Seq.empty)
      store.list() shouldBe Right(Seq.empty)
      store.stats().map(_.totalRecords) shouldBe Right(0L)
    }

    "find the nearest records first, scoring cosine on one scale on every engine" in withOpenSearch {
      for (engine <- Seq(OpenSearchEngine.Lucene, OpenSearchEngine.Faiss)) {
        val store = newStore(engine = engine)
        store.upsertBatch(records) shouldBe Right(())

        val results = store.search(Array(1f, 0f, 0f), topK = 3).fold(e => fail(e.formatted), identity)
        results.map(_.record.id).take(2) shouldBe Seq("doc-1", "doc-2")
        // (1 + cos) / 2: cos 1 -> 1.0, cos 0.6 -> 0.8, cos 0 -> 0.5
        results.map(_.score).take(2) match {
          case Seq(first, second) =>
            first shouldBe 1.0 +- 0.01
            second shouldBe 0.8 +- 0.01
          case other => fail(s"expected two scores, got $other")
        }
        results.foreach(r => r.score should ((be >= 0.0).and(be <= 1.0)))
      }
    }

    "score inner product and l2 within [0, 1]" in withOpenSearch {
      for (space <- Seq(OpenSearchSpaceType.InnerProduct, OpenSearchSpaceType.L2)) {
        val store = newStore(spaceType = space)
        store.upsertBatch(records) shouldBe Right(())

        val results = store.search(Array(1f, 0f, 0f), topK = 2).fold(e => fail(e.formatted), identity)
        results.map(_.record.id) shouldBe Seq("doc-1", "doc-2")
        results.head.score should be > results(1).score
        results.foreach(r => r.score should ((be >= 0.0).and(be <= 1.0)))
      }
    }

    "apply every kind of metadata filter, inside the kNN search too" in withOpenSearch {
      val store = newStore()
      store.upsertBatch(records) shouldBe Right(())
      import MetadataFilter.*
      def ids(filter: MetadataFilter): Set[String] =
        store.list(filter = Some(filter)).fold(e => fail(e.formatted), _.map(_.id).toSet)

      ids(All) shouldBe records.map(_.id).toSet
      ids(Equals("lang", "en")) shouldBe Set("doc-1", "doc-2")
      ids(Contains("path", "guides/")) shouldBe Set("doc-1", "doc-2")
      ids(HasKey("draft")) shouldBe Set("doc-2")
      ids(In("lang", Set("fr", "de"))) shouldBe Set("doc-3", "other-1")
      ids(In("lang", Set.empty)) shouldBe empty
      ids(Equals("lang", "en").and(!HasKey("draft"))) shouldBe Set("doc-1")
      ids(Equals("lang", "fr").or(Equals("lang", "de"))) shouldBe Set("doc-3", "other-1")

      store.search(Array(1f, 0f, 0f), topK = 5, filter = Some(Equals("lang", "fr"))).map(_.map(_.record.id)) shouldBe
        Right(Seq("doc-3"))
      store.search(Array(1f, 0f, 0f), topK = 5, filter = Some(In("lang", Set("de", "fr")))).map(_.size) shouldBe
        Right(2)
      store.count(Some(Equals("lang", "en"))) shouldBe Right(2L)
    }

    "filter inside the kNN search on Faiss" in withOpenSearch {
      val store = newStore(engine = OpenSearchEngine.Faiss)
      store.upsertBatch(records) shouldBe Right(())

      store
        .search(Array(1f, 0f, 0f), topK = 1, filter = Some(MetadataFilter.Equals("lang", "fr")))
        .map(_.map(_.record.id)) shouldBe Right(Seq("doc-3"))
    }

    "post-filter the kNN hits on NMSLIB, while the cluster still accepts it" in withOpenSearch {
      val store = newStore(engine = OpenSearchEngine.Nmslib)
      store.upsertBatch(records) match {
        case Left(e) if e.message.toLowerCase.contains("nmslib") =>
          cancel(s"this OpenSearch refuses new NMSLIB indexes: ${e.message}")
        case other => other shouldBe Right(())
      }

      store
        .search(Array(1f, 0f, 0f), topK = 1, filter = Some(MetadataFilter.Equals("lang", "fr")))
        .map(_.map(_.record.id)) shouldBe Right(Seq("doc-3"))
    }

    "list in insertion order, page by page" in withOpenSearch {
      val store = newStore()
      store.upsertBatch(records) shouldBe Right(())

      store.list(limit = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-1", "doc-2"))
      store.list(limit = 2, offset = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-3", "other-1"))
    }

    "get several records, skipping missing ones" in withOpenSearch {
      val store = newStore()
      store.upsertBatch(records) shouldBe Right(())

      store.getBatch(Seq("doc-1", "nope", "doc-3")).map(_.map(_.id).toSet) shouldBe Right(Set("doc-1", "doc-3"))
    }

    "replace a record upserted again" in withOpenSearch {
      val store = newStore()
      store.upsert(records.head) shouldBe Right(())
      val updated = records.head.withMetadata("lang", "es")
      store.upsert(updated) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(updated))
      store.count() shouldBe Right(1L)
    }

    "delete by ID, by prefix and by filter" in withOpenSearch {
      val store = newStore()
      store.upsertBatch(records) shouldBe Right(())

      store.delete("doc-3") shouldBe Right(())
      store.deleteBatch(Seq("never-there")) shouldBe Right(())
      store.deleteByPrefix("other-") shouldBe Right(1L)
      store.deleteByFilter(MetadataFilter.HasKey("draft")) shouldBe Right(1L)

      store.list().map(_.map(_.id)) shouldBe Right(Seq("doc-1"))
    }

    "report its statistics, and clear everything" in withOpenSearch {
      val store = newStore()
      store.upsertBatch(records) shouldBe Right(())

      val stats = store.stats().fold(e => fail(e.formatted), identity)
      stats.totalRecords shouldBe 4L
      stats.dimensions shouldBe Set(3)

      store.clear() shouldBe Right(())
      store.count() shouldBe Right(0L)
      store.upsert(records.head) shouldBe Right(())
      store.count() shouldBe Right(1L)
    }
  }
}
