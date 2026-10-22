package org.llm4s.vectorstore.elasticsearch

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * [[ElasticsearchVectorStore]] against a real Elasticsearch (#1469).
 *
 * Set `ELASTICSEARCH_TEST_URL` to enable it; without it every test is cancelled. A single node with security
 * disabled is enough:
 * {{{
 * docker run -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
 *   docker.elastic.co/elasticsearch/elasticsearch:8.15.0
 * export ELASTICSEARCH_TEST_URL=http://localhost:9200
 * sbt "it/testOnly org.llm4s.vectorstore.elasticsearch.ElasticsearchVectorStoreSpec"
 * }}}
 * For a secured cluster set `ELASTICSEARCH_TEST_API_KEY` (an encoded API key) as well.
 */
@Docker
class ElasticsearchVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  private val testUrl = sys.env.get("ELASTICSEARCH_TEST_URL")
  private val apiKey  = sys.env.get("ELASTICSEARCH_TEST_API_KEY").filter(_.nonEmpty)

  private var store: ElasticsearchVectorStore = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    testUrl.foreach { url =>
      val config = ElasticsearchConfig(
        baseUrl = url,
        index = s"llm4s-it-${System.nanoTime()}",
        auth = apiKey.fold[ElasticsearchAuth](ElasticsearchAuth.NoAuth)(ElasticsearchAuth.ApiKey(_))
      )
      store = ElasticsearchVectorStore(config).fold(e => fail(s"Failed to create store: ${e.formatted}"), identity)
    }

  override def afterEach(): Unit =
    if (store != null) {
      store.clear()
      store.close()
      store = null
    }

  private def withElasticsearch(test: => Unit): Unit = {
    Tier.require(testUrl.isDefined, "ELASTICSEARCH_TEST_URL not set")
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

  "ElasticsearchVectorStore" should {

    "store and read back a record" in withElasticsearch {
      store.upsert(records.head) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(records.head))
      store.get("missing") shouldBe Right(None)
    }

    "answer as an empty store before anything is written" in withElasticsearch {
      store.count() shouldBe Right(0L)
      store.search(Array(1f, 0f, 0f), topK = 3) shouldBe Right(Seq.empty)
      store.list() shouldBe Right(Seq.empty)
      store.stats().map(_.totalRecords) shouldBe Right(0L)
    }

    "find the nearest records first" in withElasticsearch {
      store.upsertBatch(records) shouldBe Right(())

      val results = store.search(Array(1f, 0f, 0f), topK = 2).fold(e => fail(e.formatted), identity)
      results.map(_.record.id) shouldBe Seq("doc-1", "doc-2")
      results.head.score should be > results(1).score
      results.foreach(r => r.score should ((be >= 0.0).and(be <= 1.0)))
    }

    "apply every kind of metadata filter" in withElasticsearch {
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
      store.count(Some(Equals("lang", "en"))) shouldBe Right(2L)
    }

    "list in insertion order, page by page" in withElasticsearch {
      store.upsertBatch(records) shouldBe Right(())

      store.list(limit = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-1", "doc-2"))
      store.list(limit = 2, offset = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-3", "other-1"))
    }

    "get several records, skipping missing ones" in withElasticsearch {
      store.upsertBatch(records) shouldBe Right(())

      store.getBatch(Seq("doc-1", "nope", "doc-3")).map(_.map(_.id).toSet) shouldBe Right(Set("doc-1", "doc-3"))
    }

    "replace a record upserted again" in withElasticsearch {
      store.upsert(records.head) shouldBe Right(())
      val updated = records.head.withMetadata("lang", "es")
      store.upsert(updated) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(updated))
      store.count() shouldBe Right(1L)
    }

    "delete by ID, by prefix and by filter" in withElasticsearch {
      store.upsertBatch(records) shouldBe Right(())

      store.delete("doc-3") shouldBe Right(())
      store.deleteBatch(Seq("never-there")) shouldBe Right(())
      store.deleteByPrefix("other-") shouldBe Right(1L)
      store.deleteByFilter(MetadataFilter.HasKey("draft")) shouldBe Right(1L)

      store.list().map(_.map(_.id)) shouldBe Right(Seq("doc-1"))
    }

    "report its statistics, and clear everything" in withElasticsearch {
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
