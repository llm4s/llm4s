package org.llm4s.vectorstore

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.postgresql.ds.PGSimpleDataSource
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sql.DataSource
import scala.util.Using

/**
 * The pgvector stores and indexes over a caller-owned `javax.sql.DataSource` (#2300): each works on pgjdbc's
 * `PGSimpleDataSource`, which is not HikariCP's, and closing a store leaves the caller's pool open. The ownership rule
 * without a database is `PgCallerOwnedDataSourceSpec` (`modules/rag`).
 *
 * Tier 2 (`@Docker`): needs `PGVECTOR_TEST_URL` (and `PGVECTOR_TEST_USER`, `PGVECTOR_TEST_PASSWORD`), as CI's
 * `Integration Tests` job sets against its pgvector service container.
 */
@Docker
class PgCallerOwnedDataSourceIntegrationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val testUrl  = sys.env.get("PGVECTOR_TEST_URL")
  private val user     = sys.env.getOrElse("PGVECTOR_TEST_USER", "postgres")
  private val password = sys.env.getOrElse("PGVECTOR_TEST_PASSWORD", "")
  private val suffix   = System.currentTimeMillis()

  private def requirePg(): String = {
    Tier.require(testUrl.isDefined, "PGVECTOR_TEST_URL not set")
    testUrl.get
  }

  private def simpleDataSource(): PGSimpleDataSource = {
    val ds = new PGSimpleDataSource()
    ds.setURL(requirePg())
    ds.setUser(user)
    ds.setPassword(password)
    ds
  }

  /** The caller's own pool, which a store must leave open. */
  private def callerPool(): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(requirePg())
    config.setUsername(user)
    config.setPassword(password)
    config.setMaximumPoolSize(4)
    new HikariDataSource(config)
  }

  private def drop(ds: DataSource, tables: String*): Unit =
    Using(ds.getConnection)(c =>
      Using(c.createStatement())(s => tables.foreach(t => s.execute(s"DROP TABLE IF EXISTS $t")))
    ): Unit

  private def stillOpen(pool: HikariDataSource): Unit = {
    pool.isClosed shouldBe false
    Using.resource(pool.getConnection)(_.isValid(5)) shouldBe true
  }

  private val record = VectorRecord("doc-1", Array(0.1f, 0.2f, 0.3f), Some("hello world"), Map("k" -> "v"))

  "PgVectorStore" should "work over a PGSimpleDataSource" in {
    val ds    = simpleDataSource()
    val table = s"owned_ds_vectors_$suffix"
    val store = PgVectorStore.create(ds, table).value
    store.upsert(record) shouldBe Right(())
    store.get("doc-1").value.map(_.content) shouldBe Some(Some("hello world"))
    store.count().value shouldBe 1L
    store.close()
    drop(ds, table)
  }

  it should "leave a caller-owned pool open when closed" in Using.resource(callerPool()) { pool =>
    val table = s"owned_pool_vectors_$suffix"
    val store = PgVectorStore(pool: DataSource, table).value
    store.upsert(record) shouldBe Right(())
    store.close()
    stillOpen(pool)
    drop(pool, table)
  }

  "PgKeywordIndex" should "work over a PGSimpleDataSource and leave a caller-owned pool open" in {
    val ds    = simpleDataSource()
    val base  = s"owned_ds_kw_$suffix"
    val index = PgKeywordIndex(ds, base).value
    index.index(KeywordDocument("doc-1", "the quick brown fox")) shouldBe Right(())
    index.search("fox", 5).value.map(_.id) shouldBe Seq("doc-1")
    index.close()

    Using.resource(callerPool()) { pool =>
      PgKeywordIndex(pool: DataSource, base, "english").value.close()
      stillOpen(pool)
    }
    drop(ds, s"${base}_keyword")
  }

  "HybridSearcher.pgvectorShared" should "search over a PGSimpleDataSource and leave a caller-owned pool open" in {
    val ds       = simpleDataSource()
    val vectors  = s"owned_ds_hybrid_v_$suffix"
    val keywords = s"owned_ds_hybrid_k_$suffix"
    val searcher = HybridSearcher.pgvectorShared(ds, vectors, keywords).value
    searcher.vectorStore.upsert(record) shouldBe Right(())
    searcher.keywordIndex.index(KeywordDocument("doc-1", "hello world")) shouldBe Right(())
    searcher.search(record.embedding, "hello", topK = 5).value.map(_.id) shouldBe Seq("doc-1")
    searcher.close()

    Using.resource(callerPool()) { pool =>
      HybridSearcher.pgvectorShared(pool: DataSource, vectors, keywords, FusionStrategy.default).value.close()
      stillOpen(pool)
    }
    drop(ds, vectors, s"${keywords}_keyword")
  }

  "HybridSearcher.pgvectorShared(connectionString)" should "close the pool it created when the searcher closes" in {
    val vectors  = s"own_pool_hybrid_v_$suffix"
    val keywords = s"own_pool_hybrid_k_$suffix"
    val searcher = HybridSearcher.pgvectorShared(requirePg(), user, password, vectors, keywords).value
    searcher.vectorStore.upsert(record) shouldBe Right(())
    searcher.close()
    // The pool is gone, so the store can no longer reach the database.
    searcher.vectorStore.count().isLeft shouldBe true
    drop(simpleDataSource(), vectors, s"${keywords}_keyword")
  }

  "PgSearchIndex.fromDataSource" should "work over a PGSimpleDataSource and leave a caller-owned pool open" in {
    val ds    = simpleDataSource()
    val table = s"owned_ds_search_$suffix"
    val index = PgSearchIndex.fromDataSource(ds, table).value
    index.initializeSchema() shouldBe Right(())
    index.pgConfig shouldBe None
    index.close()

    Using.resource(callerPool()) { pool =>
      val pooled = PgSearchIndex.fromDataSource(pool, table).value
      pooled.initializeSchema() shouldBe Right(())
      pooled.close()
      stillOpen(pool)
    }
    drop(ds, table)
  }
}
