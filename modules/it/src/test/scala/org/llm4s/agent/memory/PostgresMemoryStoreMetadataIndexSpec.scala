package org.llm4s.agent.memory

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.agent.memory.PostgresMemoryStore.SqlParam.PString
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Using

/**
 * `ByMetadata` and `ByEntity` on `PostgresMemoryStore` are served by the table's `GIN(metadata)` index again (#3164):
 * on 100,000 rows the plan of each is a bitmap index scan on `idx_<table>_metadata`, and the rows they match are still
 * exactly those the text comparison of #3031 matches.
 *
 * Tier 2 (`@Docker`), enabled as `PostgresMemoryStoreSpec` is: `POSTGRES_TEST_ENABLED=true`, with the connection from
 * `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD`.
 */
@Docker
class PostgresMemoryStoreMetadataIndexSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private def isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private val table = s"gin_index_${System.currentTimeMillis()}"
  private val rows  = 100000

  private var pool: HikariDataSource     = _
  private var store: PostgresMemoryStore = _

  private def right[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  /** The pool and the loaded table, made on first use so a disabled tier connects to nothing. */
  private def loaded(): (HikariDataSource, PostgresMemoryStore) = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    if (pool == null) {
      val config = new HikariConfig()
      val host   = sys.env.getOrElse("POSTGRES_HOST", "localhost")
      val port   = sys.env.getOrElse("POSTGRES_PORT", "5432")
      val db     = sys.env.getOrElse("POSTGRES_DB", "postgres")
      config.setJdbcUrl(s"jdbc:postgresql://$host:$port/$db")
      config.setUsername(sys.env.getOrElse("POSTGRES_USER", "postgres"))
      config.setPassword(sys.env.getOrElse("POSTGRES_PASSWORD", "password"))
      config.setMaximumPoolSize(2)
      pool = new HikariDataSource(config)
      store = right(PostgresMemoryStore.fromDataSource(pool, table, None))
      // 100,000 rows: 1,000 entities, a numeric `count` of 500 values, a string `tag` of 2,000
      Using.resource(pool.getConnection)(c =>
        Using.resource(c.createStatement()) { s =>
          s.execute(
            s"""INSERT INTO $table (id, content, memory_type, metadata, created_at)
               |SELECT 'm' || i, 'memory ' || i, 'entity',
               |  jsonb_build_object('entity_id', 'e' || (i % 1000), 'count', i % 500, 'tag', 't' || (i % 2000)),
               |  now()
               |FROM generate_series(1, $rows) AS i""".stripMargin
          )
          s.execute(s"ANALYZE $table")
        }
      )
    }
    (pool, store)
  }

  override def afterAll(): Unit =
    if (pool != null) {
      Using.resource(pool.getConnection)(c =>
        Using.resource(c.createStatement())(s =>
          Seq(table, s"${table}_applied", s"${table}_fences").foreach(t => s.execute(s"DROP TABLE IF EXISTS $t"))
        )
      )
      pool.close()
    }

  /** The plan of the store's own condition for `filter`, as a recall of the default tenant runs it. */
  private def plan(filter: MemoryFilter): String = {
    val (ds, _) = loaded()
    val sql     = PostgresMemoryStore.narrowing(filter).sql.getOrElse(fail(s"no SQL for $filter"))
    Using.resource(ds.getConnection) { c =>
      Using.resource(c.prepareStatement(s"EXPLAIN SELECT id FROM $table WHERE tenant = '' AND (${sql.clause})")) { st =>
        sql.params.zipWithIndex.foreach {
          case (PString(v), i) => st.setString(i + 1, v)
          case (other, _)      => fail(s"unexpected parameter $other")
        }
        Using.resource(st.executeQuery()) { rs =>
          Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).mkString("\n")
        }
      }
    }
  }

  private val index = s"Bitmap Index Scan on idx_${table}_metadata"

  "PostgresMemoryStore" should "serve ByMetadata of a string from the GIN metadata index" in {
    plan(MemoryFilter.ByMetadata("tag", "t7")) should include(index)
  }

  it should "serve ByMetadata of a number's text from the GIN metadata index" in {
    plan(MemoryFilter.ByMetadata("count", "3")) should include(index)
  }

  it should "serve ByEntity from the GIN metadata index" in {
    plan(MemoryFilter.ByEntity(EntityId("e5"))) should include(index)
  }

  it should "still match exactly the rows whose value reads as the text" in {
    val (_, s) = loaded()
    right(s.count(MemoryFilter.ByMetadata("count", "3"))) shouldBe 200L
    right(s.count(MemoryFilter.ByMetadata("count", "3.0"))) shouldBe 0L
    right(s.count(MemoryFilter.ByMetadata("tag", "t7"))) shouldBe 50L
    right(s.count(MemoryFilter.ByEntity(EntityId("e5")))) shouldBe 100L
    right(s.count(MemoryFilter.Not(MemoryFilter.ByEntity(EntityId("e5"))))) shouldBe (rows - 100).toLong
  }
}
