package org.llm4s.vectorstore.redis

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.vectorstore.{ MetadataFilter, VectorRecord }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI

/**
 * [[RedisVectorStore]] against a real server (#1470): the same behaviour, run against Redis by
 * [[RedisVectorStoreSpec]] and against Valkey by [[ValkeyVectorStoreSpec]].
 *
 * @param envVar        the variable holding the server's `redis://host:port` URL; without it every test is cancelled
 * @param filterQueries whether the server's search module answers queries without a vector (`count`, `list`,
 *                      `deleteByFilter`) and wildcard queries (`Contains`); where it does not, filters are exercised
 *                      through `search`, whose KNN query takes them as a pre-filter
 */
abstract class RedisVectorStoreBehaviour(envVar: String, filterQueries: Boolean)
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach {

  private val testUrl = sys.env.get(envVar).filter(_.nonEmpty)

  private var store: RedisVectorStore = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    testUrl.foreach { url =>
      val uri    = new URI(url)
      val index  = s"llm4s-it-${System.nanoTime()}"
      val config = RedisConfig(host = uri.getHost, port = if (uri.getPort > 0) uri.getPort else 6379, index = index)
      store = RedisVectorStore(config).fold(e => fail(s"Failed to create store: ${e.formatted}"), identity)
    }

  override def afterEach(): Unit =
    if (store != null) {
      store.clear()
      store.close()
      store = null
    }

  private def withServer(test: => Unit): Unit = {
    Tier.require(testUrl.isDefined, s"$envVar not set")
    test
  }

  private def rec(id: String, v: Array[Float], meta: (String, String)*): VectorRecord =
    VectorRecord(id, v, Some(s"content of $id"), meta.toMap)

  private val records = Seq(
    rec("doc-1", Array(1f, 0f, 0f), "lang"     -> "en", "path" -> "/guides/scala.md"),
    rec("doc-2", Array(0.6f, 0.8f, 0f), "lang" -> "en", "path" -> "/guides/java.md", "draft" -> "yes"),
    rec("doc-3", Array(0f, 1f, 0f), "lang"     -> "fr", "path" -> "/notes/a, b.txt"),
    rec("other-1", Array(0f, 0f, 1f), "lang"   -> "de")
  )

  "RedisVectorStore" should {

    "store and read back a record" in withServer {
      store.upsert(records.head) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(records.head))
      store.get("missing") shouldBe Right(None)
    }

    "answer as an empty store before anything is written" in withServer {
      store.count() shouldBe Right(0L)
      store.search(Array(1f, 0f, 0f), topK = 3) shouldBe Right(Seq.empty)
      store.list() shouldBe Right(Seq.empty)
      store.stats().map(_.totalRecords) shouldBe Right(0L)
    }

    "find the nearest records first" in withServer {
      store.upsertBatch(records) shouldBe Right(())

      val results = store.search(Array(1f, 0f, 0f), topK = 2).fold(e => fail(e.formatted), identity)
      results.map(_.record.id) shouldBe Seq("doc-1", "doc-2")
      results.head.score should be > results(1).score
      results.head.score shouldBe 1.0 +- 1e-5
      results.foreach(r => r.score should ((be >= 0.0).and(be <= 1.0)))
    }

    "apply every kind of metadata filter" in withServer {
      store.upsertBatch(records) shouldBe Right(())
      import MetadataFilter.*
      def ids(filter: MetadataFilter): Set[String] =
        if (filterQueries) store.list(filter = Some(filter)).fold(e => fail(e.formatted), _.map(_.id).toSet)
        else
          store
            .search(Array(1f, 1f, 1f), topK = 10, filter = Some(filter))
            .fold(e => fail(e.formatted), _.map(_.record.id).toSet)

      ids(All) shouldBe records.map(_.id).toSet
      ids(Equals("lang", "en")) shouldBe Set("doc-1", "doc-2")
      ids(Equals("path", "/notes/a, b.txt")) shouldBe Set("doc-3")
      ids(Equals("lang", "EN")) shouldBe empty
      ids(HasKey("draft")) shouldBe Set("doc-2")
      ids(In("lang", Set("fr", "de"))) shouldBe Set("doc-3", "other-1")
      ids(In("lang", Set.empty)) shouldBe empty
      ids(Equals("lang", "en").and(!HasKey("draft"))) shouldBe Set("doc-1")
      ids(Equals("lang", "fr").or(Equals("lang", "de"))) shouldBe Set("doc-3", "other-1")
      ids(!Equals("lang", "en")) shouldBe Set("doc-3", "other-1")
      if (filterQueries) {
        ids(Contains("path", "guides/")) shouldBe Set("doc-1", "doc-2")
        ids(Contains("path", ", b")) shouldBe Set("doc-3")
        store.count(Some(Equals("lang", "en"))) shouldBe Right(2L)
      }

      store.search(Array(1f, 0f, 0f), topK = 5, filter = Some(Equals("lang", "fr"))).map(_.map(_.record.id)) shouldBe
        Right(Seq("doc-3"))
    }

    "list in insertion order, page by page" in withServer {
      assume(filterQueries, "the server's search module has no query without a vector")
      store.upsertBatch(records) shouldBe Right(())

      store.list(limit = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-1", "doc-2"))
      store.list(limit = 2, offset = 2).map(_.map(_.id)) shouldBe Right(Seq("doc-3", "other-1"))
    }

    "get several records, skipping missing ones" in withServer {
      store.upsertBatch(records) shouldBe Right(())

      store.getBatch(Seq("doc-1", "nope", "doc-3")).map(_.map(_.id)) shouldBe Right(Seq("doc-1", "doc-3"))
    }

    "replace a record upserted again" in withServer {
      store.upsert(records.head) shouldBe Right(())
      val updated = records.head.copy(content = None).withMetadata("lang", "es")
      store.upsert(updated) shouldBe Right(())

      store.get("doc-1") shouldBe Right(Some(updated))
      store.search(Array(1f, 0f, 0f), topK = 5).map(_.map(_.record)) shouldBe Right(Seq(updated))
      if (filterQueries) {
        store.count() shouldBe Right(1L)
        store.count(Some(MetadataFilter.Equals("lang", "en"))) shouldBe Right(0L)
      }
    }

    "delete by ID, by prefix and by filter" in withServer {
      store.upsertBatch(records) shouldBe Right(())

      store.delete("doc-3") shouldBe Right(())
      store.deleteBatch(Seq("never-there")) shouldBe Right(())
      store.deleteByPrefix("other-") shouldBe Right(1L)
      if (filterQueries) {
        store.deleteByFilter(MetadataFilter.HasKey("draft")) shouldBe Right(1L)
        store.list().map(_.map(_.id)) shouldBe Right(Seq("doc-1"))
      } else
        store.search(Array(1f, 0f, 0f), topK = 5).map(_.map(_.record.id)) shouldBe Right(Seq("doc-1", "doc-2"))
    }

    "report its statistics, and clear everything" in withServer {
      store.upsertBatch(records) shouldBe Right(())

      if (filterQueries) {
        val stats = store.stats().fold(e => fail(e.formatted), identity)
        stats.totalRecords shouldBe 4L
        stats.dimensions shouldBe Set(3)
      }

      store.clear() shouldBe Right(())
      store.count() shouldBe Right(0L)
      store.get("doc-1") shouldBe Right(None)
      store.upsert(records.head) shouldBe Right(())
      store.search(Array(1f, 0f, 0f), topK = 5).map(_.map(_.record.id)) shouldBe Right(Seq("doc-1"))
    }
  }
}

/**
 * [[RedisVectorStore]] against Redis with the Redis Query Engine (#1470).
 *
 * Set `REDIS_TEST_URL` to enable it; without it every test is cancelled:
 * {{{
 * docker run -p 6379:6379 redis/redis-stack-server:7.4.0-v8
 * export REDIS_TEST_URL=redis://localhost:6379
 * sbt "it/testOnly org.llm4s.vectorstore.redis.RedisVectorStoreSpec"
 * }}}
 */
@Docker
class RedisVectorStoreSpec extends RedisVectorStoreBehaviour("REDIS_TEST_URL", filterQueries = true)

/**
 * [[RedisVectorStore]] against Valkey with `valkey-search` (#1470). The `valkey-search` in Valkey 9.0 answers only
 * vector queries, without `SORTBY` or wildcards, so `count`, `list`, `stats` and `deleteByFilter` over an index, and
 * `Contains`, are not exercised; every other filter is, through `search`.
 *
 * Set `VALKEY_TEST_URL` to enable it; without it every test is cancelled:
 * {{{
 * docker run -p 6380:6379 valkey/valkey-bundle:9.0.6
 * export VALKEY_TEST_URL=redis://localhost:6380
 * sbt "it/testOnly org.llm4s.vectorstore.redis.ValkeyVectorStoreSpec"
 * }}}
 */
@Docker
class ValkeyVectorStoreSpec extends RedisVectorStoreBehaviour("VALKEY_TEST_URL", filterQueries = false)
