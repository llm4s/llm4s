package org.llm4s.agent.memory

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker

import java.util.concurrent.{ Executors, TimeUnit }
import scala.util.Try

/**
 * `TransactionalMemoryStoreContract` (design §4.31) over [[PostgresMemoryStore]]: applied writes, fencing by token
 * per scope, bounds enforced by eviction in the transaction, atomic `replace` - plus two stores on one table, as two
 * processes would be, writing at once within one bound.
 *
 * Tier 2 (`@Docker`), run by CI's `Integration Tests` job (`sbt testIntegration`) against its pgvector service.
 * Locally it is cancelled unless `POSTGRES_TEST_ENABLED=true`; see `PostgresMemoryStoreSpec` for the docker command
 * and the connection variables.
 */
@Docker
class PostgresTransactionalMemoryStoreSpec extends TransactionalMemoryStoreContract {

  private def isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private def config(table: String) = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = sys.env.getOrElse("POSTGRES_PASSWORD", "password"),
    tableName = table,
    maxPoolSize = 4
  )

  private def freshTable(): String = s"test_applied_${java.util.UUID.randomUUID().toString.replace("-", "").take(16)}"

  private def open(table: String): PostgresMemoryStore =
    PostgresMemoryStore(config(table)).fold(e => fail(e.message), identity)

  /** Drops the table and its two applied-write tables, then closes the store. */
  private def dropped(store: PostgresMemoryStore): Unit = {
    Try {
      val conn = java.sql.DriverManager.getConnection(
        config(store.tableName).jdbcUrl,
        config(store.tableName).user,
        config(store.tableName).password
      )
      TransactionalMemoryStoreContract.after(conn.close()) {
        val st = conn.createStatement()
        Seq("", "_applied", "_fences").foreach(suffix => st.execute(s"DROP TABLE IF EXISTS ${store.tableName}$suffix"))
        st.close()
      }
    }
    store.close()
  }

  protected def storeName: String = "PostgresMemoryStore"

  protected def newStore(): TransactionalMemoryStore = open(freshTable())

  override protected def withStore[A](body: TransactionalMemoryStore => A): A = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val store = open(freshTable())
    TransactionalMemoryStoreContract.after(dropped(store))(body(store))
  }

  "Two PostgresMemoryStores on one table" should "keep one bound while both write at once" in {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val table  = freshTable()
    val first  = open(table)
    val second = open(table)
    TransactionalMemoryStoreContract.after { second.close(); dropped(first) } {
      val bounds = MemoryBounds(maxMemories = 5)
      val pool   = Executors.newFixedThreadPool(8)
      val writes = (1 to 40).map { i =>
        val store = if (i % 2 == 0) first else second
        pool.submit { () =>
          store.applyAtomically(
            AppliedWriteKey(s"scope-$i", s"key-$i"),
            1L,
            Seq(Memory.userFact(s"fact $i")),
            Nil,
            bounds
          )
        }
      }
      val results = writes.map(_.get(60, TimeUnit.SECONDS))
      pool.shutdown()
      results.foreach(r => r.isRight shouldBe true)
      first.count().value shouldBe 5L
      first.appliedWriteCount.value shouldBe 40
    }
  }
}
