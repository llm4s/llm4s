package org.llm4s.agent.memory

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.postgresql.ds.PGSimpleDataSource
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sql.DataSource
import scala.util.Using

/**
 * `PostgresMemoryStore.fromDataSource` over a caller-owned `javax.sql.DataSource` (#2300): it works on pgjdbc's
 * `PGSimpleDataSource`, which is not HikariCP's, and closing the store leaves the caller's pool open. The ownership
 * rule without a database is `PostgresMemoryStoreDataSourceSpec` (`modules/memory-postgres`).
 *
 * Tier 2 (`@Docker`), enabled as `PostgresMemoryStoreSpec` is: `POSTGRES_TEST_ENABLED=true`, with the connection from
 * `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD`.
 */
@Docker
class PostgresMemoryStoreDataSourceIntegrationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)
  private val host      = sys.env.getOrElse("POSTGRES_HOST", "localhost")
  private val port      = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt
  private val database  = sys.env.getOrElse("POSTGRES_DB", "postgres")
  private val user      = sys.env.getOrElse("POSTGRES_USER", "postgres")
  private val password  = sys.env.getOrElse("POSTGRES_PASSWORD", "password")
  private val suffix    = System.currentTimeMillis()

  private def requirePg(): Unit = Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")

  private def simpleDataSource(): PGSimpleDataSource = {
    requirePg()
    val ds = new PGSimpleDataSource()
    ds.setServerNames(Array(host))
    ds.setPortNumbers(Array(port))
    ds.setDatabaseName(database)
    ds.setUser(user)
    ds.setPassword(password)
    ds
  }

  private def callerPool(): HikariDataSource = {
    requirePg()
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:postgresql://$host:$port/$database")
    config.setUsername(user)
    config.setPassword(password)
    config.setMaximumPoolSize(4)
    new HikariDataSource(config)
  }

  private def drop(ds: DataSource, table: String): Unit =
    Using(ds.getConnection) { c =>
      Using(c.createStatement()) { s =>
        Seq(table, s"${table}_applied", s"${table}_fences").foreach(t => s.execute(s"DROP TABLE IF EXISTS $t"))
      }
    }: Unit

  "PostgresMemoryStore.fromDataSource" should "store and recall memories over a PGSimpleDataSource" in {
    val ds     = simpleDataSource()
    val table  = s"owned_ds_memories_$suffix"
    val store  = PostgresMemoryStore.fromDataSource(ds, table, Some(MockEmbeddingService.default)).value
    val memory = Memory.fromConversation("I prefer Scala", "user", Some("conv-1"))
    store.store(memory).isRight shouldBe true
    store.get(memory.id).value.map(_.content) shouldBe Some("I prefer Scala")
    store.count().value shouldBe 1L
    store.close()
    drop(ds, table)
  }

  it should "leave a caller-owned pool open when the store closes" in Using.resource(callerPool()) { pool =>
    val table = s"owned_pool_memories_$suffix"
    val store = PostgresMemoryStore.fromDataSource(pool, table).value
    store.store(Memory.fromConversation("hello", "user")).isRight shouldBe true
    store.close()
    pool.isClosed shouldBe false
    Using.resource(pool.getConnection)(_.isValid(5)) shouldBe true
    // A second store over the same pool still works after the first one closed.
    val again = PostgresMemoryStore.fromDataSource(pool, table).value
    again.count().value shouldBe 1L
    again.close()
    drop(pool, table)
  }

  "PostgresMemoryStore.apply" should "close the pool it created when the store closes" in {
    requirePg()
    val table = s"own_pool_memories_$suffix"
    val store = PostgresMemoryStore(
      PostgresMemoryStore.Config(host, port, database, user, password, table, maxPoolSize = 2)
    ).value
    store.close()
    store.count().isLeft shouldBe true
    drop(simpleDataSource(), table)
  }
}
