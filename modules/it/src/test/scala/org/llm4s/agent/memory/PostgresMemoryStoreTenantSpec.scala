package org.llm4s.agent.memory

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{ Connection, DriverManager }
import scala.util.Try

/**
 * [[PostgresMemoryStore]]'s tenant (#3032): many tenants share one table, and each store reads, counts, evicts, clears
 * and records applied writes only for its own tenant. A table created before the column existed is migrated when a
 * store opens it, its rows becoming the rows of no tenant.
 *
 * Tier 2 (`@Docker`), run by CI's `Integration Tests` job (`sbt testIntegration`) against its pgvector service.
 * Locally it is cancelled unless `POSTGRES_TEST_ENABLED=true`; see `PostgresMemoryStoreSpec` for the docker command
 * and the connection variables.
 */
@Docker
class PostgresMemoryStoreTenantSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private def config(table: String, tenant: Option[String] = None) = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = Secret(sys.env.getOrElse("POSTGRES_PASSWORD", "password")),
    tableName = table,
    maxPoolSize = 2,
    tenant = tenant
  )

  private def freshTable(): String = s"test_tenant_${java.util.UUID.randomUUID().toString.replace("-", "").take(16)}"

  private def open(table: String, tenant: Option[String]): PostgresMemoryStore =
    PostgresMemoryStore(config(table, tenant)).fold(e => fail(e.message), identity)

  private def connect(table: String): Connection = {
    val c = config(table)
    DriverManager.getConnection(c.jdbcUrl, c.user, c.password.reveal)
  }

  /** `body` with a fresh table name; the table and its applied-write tables are dropped afterwards. */
  private def withTable[A](body: String => A): A = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val table = freshTable()
    TransactionalMemoryStoreContract.after {
      Try {
        val conn = connect(table)
        TransactionalMemoryStoreContract.after(conn.close()) {
          val st = conn.createStatement()
          Seq("", "_applied", "_fences").foreach(suffix => st.execute(s"DROP TABLE IF EXISTS $table$suffix"))
          st.close()
        }
      }: Unit
    }(body(table))
  }

  private def contents(store: MemoryStore): Vector[String] =
    store.recall(MemoryFilter.All, Int.MaxValue).value.map(_.content).toVector.sorted

  "Two tenants of one table" should "never see, count, delete, clear or evict each other's memories" in withTable {
    table =>
      val alice = open(table, Some("alice"))
      val bob   = open(table, Some("bob"))
      TransactionalMemoryStoreContract.after { alice.close(); bob.close() } {
        val shared = MemoryId("same-id")
        alice.storeAll(Seq(Memory(shared, "alice's fact", MemoryType.UserFact), Memory.userFact("alice two"))).value
        bob.storeAll(Seq(Memory(shared, "bob's fact", MemoryType.UserFact))).value
        contents(alice) shouldBe Vector("alice two", "alice's fact")
        contents(bob) shouldBe Vector("bob's fact")
        alice.get(shared).value.map(_.content) shouldBe Some("alice's fact")
        bob.count().value shouldBe 1L
        bob.search("alice", 10, MemoryFilter.All).value shouldBe empty
        bob.deleteMatching(MemoryFilter.All).value
        bob.delete(shared).value
        alice.count().value shouldBe 2L

        // one tenant's bound never evicts another's memories (#3032, #3034)
        bob.storeAll((1 to 3).map(i => Memory.userFact(s"bob $i"))).value
        val bounds = MemoryBounds(maxMemories = 1)
        val (_, out) =
          alice
            .applyAtomically(AppliedWriteKey("thread", "k"), 1L, Seq(Memory.userFact("alice new")), Nil, bounds)
            .value
        out.evicted shouldBe 2
        contents(alice) shouldBe Vector("alice new")
        bob.count().value shouldBe 3L

        // applied writes and fences are the tenant's too
        bob.appliedWrite(AppliedWriteKey("thread", "k"), 1L).value._2 shouldBe None
        bob.appliedWriteCount.value shouldBe 0
        alice.appliedWriteCount.value shouldBe 1

        bob.clear().value
        alice.count().value shouldBe 1L
      }
  }

  "storageIdentity" should "name the table and tenant, whichever store instance reaches them" in withTable { table =>
    val a1 = open(table, Some("alice"))
    val a2 = open(table, Some("alice"))
    val b  = open(table, Some("bob"))
    val n  = open(table, None)
    TransactionalMemoryStoreContract.after { a1.close(); a2.close(); b.close(); n.close() } {
      val alice = a1.storageIdentity.value
      alice shouldBe defined
      a2.storageIdentity.value shouldBe alice
      b.storageIdentity.value should not be alice
      n.storageIdentity.value should not be alice
      n.storageIdentity.value should not be b.storageIdentity.value
      // the cluster is its system identifier, never the address the connection came in on (Codex on #3166)
      (alice.get should not).include("unknown")
      val host = config(table).host
      if (host == "localhost") {
        val byAddress =
          PostgresMemoryStore(config(table, Some("alice")).copy(host = "127.0.0.1"))
            .fold(e => fail(e.message), identity)
        TransactionalMemoryStoreContract.after(byAddress.close())(byAddress.storageIdentity.value shouldBe alice)
      }
    }
  }

  // Codex on #3166: opening a store over an up-to-date table never waits for the table's readers
  "Opening a store over a migrated table" should "take no lock that waits for a reader" in withTable { table =>
    open(table, Some("alice")).close()
    val reader = connect(table)
    TransactionalMemoryStoreContract.after(reader.close()) {
      reader.setAutoCommit(false)
      val st = reader.createStatement()
      st.execute(s"LOCK TABLE $table IN ACCESS SHARE MODE") // a long read, holding its lock until it ends
      Seq("", "_applied", "_fences").foreach(suffix => st.execute(s"LOCK TABLE $table$suffix IN ACCESS SHARE MODE"))
      val pool   = java.util.concurrent.Executors.newSingleThreadExecutor()
      val opened = pool.submit(() => open(table, Some("bob")))
      val store  = opened.get(20, java.util.concurrent.TimeUnit.SECONDS)
      store.close()
      pool.shutdown()
      reader.rollback()
    }
  }

  "A table created before tenants" should "be migrated on open, its rows the rows of no tenant" in withTable { table =>
    val conn = connect(table)
    TransactionalMemoryStoreContract.after(conn.close()) {
      val st = conn.createStatement()
      st.execute("CREATE EXTENSION IF NOT EXISTS vector")
      st.execute(s"""CREATE TABLE $table (
                    |  id TEXT PRIMARY KEY, content TEXT NOT NULL, memory_type TEXT NOT NULL,
                    |  metadata JSONB DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL, importance DOUBLE PRECISION,
                    |  embedding vector, version BIGINT NOT NULL DEFAULT 0)""".stripMargin)
      st.execute(s"CREATE TABLE ${table}_applied (key TEXT PRIMARY KEY, scope TEXT NOT NULL, outcome TEXT NOT NULL)")
      st.execute(s"CREATE TABLE ${table}_fences (scope TEXT PRIMARY KEY, token BIGINT NOT NULL)")
      st.execute(
        s"INSERT INTO $table (id, content, memory_type, created_at) VALUES ('old', 'an old memory', 'user_fact', now())"
      )
      st.close()
    }
    val legacy = open(table, None)
    val alice  = open(table, Some("alice"))
    TransactionalMemoryStoreContract.after { legacy.close(); alice.close() } {
      contents(legacy) shouldBe Vector("an old memory")
      contents(alice) shouldBe empty
      // the key is per tenant now: alice may hold the id the old row has
      alice.store(Memory(MemoryId("old"), "alice's", MemoryType.UserFact)).value
      contents(legacy) shouldBe Vector("an old memory")
      alice.applyAtomically(AppliedWriteKey("s", "k"), 1L, Nil, Nil, MemoryBounds()).value
      legacy.applyAtomically(AppliedWriteKey("s", "k"), 1L, Nil, Nil, MemoryBounds()).value
      legacy.appliedWriteCount.value shouldBe 1
      // a second open finds the table migrated and changes nothing
      open(table, None).close()
      contents(legacy) shouldBe Vector("an old memory")
    }
  }
}
