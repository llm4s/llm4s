package org.llm4s.agent.memory

import org.llm4s.error.{ ConfigurationError, OptimisticLockFailure }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The Postgres connection and a fresh table per case, for the suites below. */
private object PostgresContractDb {

  def isEnabled: Boolean = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  def config(table: String, dimensions: Option[Int] = None): PostgresMemoryStore.Config = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = Secret(sys.env.getOrElse("POSTGRES_PASSWORD", "password")),
    tableName = table,
    maxPoolSize = 2,
    embeddingDimensions = dimensions
  )

  def freshTable(): String = s"test_contract_${java.util.UUID.randomUUID().toString.replace("-", "").take(16)}"

  /** Runs `sql` on a connection of its own. */
  def execute(table: String, sql: String*): Unit = {
    val c    = config(table)
    val conn = java.sql.DriverManager.getConnection(c.jdbcUrl, c.user, c.password.reveal)
    MemoryStoreContract.after(conn.close()) {
      val st = conn.createStatement()
      sql.foreach(st.execute)
      st.close()
    }
  }

  /** The first column of the first row of `sql`, as text. */
  def query(table: String, sql: String): Option[String] = {
    val c    = config(table)
    val conn = java.sql.DriverManager.getConnection(c.jdbcUrl, c.user, c.password.reveal)
    MemoryStoreContract.after(conn.close()) {
      val rs = conn.createStatement().executeQuery(sql)
      if (rs.next()) Option(rs.getString(1)) else None
    }
  }

  def drop(table: String): Unit =
    Try(execute(table, Seq("", "_applied", "_fences").map(s => s"DROP TABLE IF EXISTS $table$s")*)): Unit
}

/**
 * `MemoryStoreContract` (#3038) over [[PostgresMemoryStore]] without an embedding service: a keyword store.
 *
 * Tier 2 (`@Docker`), run by CI's `Integration Tests` job against its pgvector service. Locally it is cancelled unless
 * `POSTGRES_TEST_ENABLED=true`; see `PostgresMemoryStoreSpec` for the docker command and the connection variables.
 */
@Docker
class PostgresKeywordMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "PostgresMemoryStore (keyword)"
  protected def semantic: Boolean = false
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    Tier.require(PostgresContractDb.isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val table = PostgresContractDb.freshTable()
    val store = PostgresMemoryStore(PostgresContractDb.config(table)).fold(e => fail(e.message), identity)
    MemoryStoreContract.after { store.close(); PostgresContractDb.drop(table) }(body(store))
  }
}

/** `MemoryStoreContract` over [[PostgresMemoryStore]] with an embedding service: pgvector search. `@Docker`. */
@Docker
class PostgresSemanticMemoryStoreContractSpec extends MemoryStoreContract {
  protected def storeName: String = "PostgresMemoryStore (pgvector)"
  protected def semantic: Boolean = true
  protected def withStore[A](embeddings: EmbeddingService)(body: MemoryStore => A): A = {
    Tier.require(PostgresContractDb.isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val table = PostgresContractDb.freshTable()
    val store =
      PostgresMemoryStore(PostgresContractDb.config(table), Some(embeddings)).fold(e => fail(e.message), identity)
    MemoryStoreContract.after { store.close(); PostgresContractDb.drop(table) }(body(store))
  }
}

/**
 * What only `PostgresMemoryStore` has (#3037, #3038): the optimistic lock against a concurrent `store`, a declared
 * dimension that types and indexes the column (an older untyped table converted when it can be), and `reembedAll`.
 * `@Docker`, as above.
 */
@Docker
class PostgresMemoryStoreVectorSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  import PostgresContractDb._

  private def withTable[A](body: String => A): A = {
    Tier.require(isEnabled, "POSTGRES_TEST_ENABLED=true not set")
    val table = freshTable()
    MemoryStoreContract.after(drop(table))(body(table))
  }

  private def open(table: String, dims: Option[Int], service: Option[EmbeddingService] = None) =
    PostgresMemoryStore(config(table, dims), service)

  private def withStore[A](table: String, dims: Option[Int] = None, service: Option[EmbeddingService] = None)(
    body: PostgresMemoryStore => A
  ): A = {
    val store = open(table, dims, service).fold(e => fail(e.message), identity)
    MemoryStoreContract.after(store.close())(body(store))
  }

  private def mem(id: String, vector: Array[Float]) =
    Memory(MemoryId(id), s"text $id", MemoryType.Task).withEmbedding(vector)

  "PostgresMemoryStore.update" should "fail its optimistic lock when a store overwrites the memory meanwhile" in
    withTable { table =>
      withStore(table) { store =>
        store.store(Memory(MemoryId("m"), "first", MemoryType.Task)).value
        // The update function runs between the read and the guarded write: a store of the same id lands there
        val result = store.update(
          MemoryId("m"),
          m => {
            store.store(m.copy(content = "overwritten")).value
            m.copy(content = "updated")
          }
        )
        result.left.value shouldBe a[OptimisticLockFailure]
        store.get(MemoryId("m")).value.map(_.content) shouldBe Some("overwritten")
      }
    }

  "A declared dimension" should "type the column vector(n) and index it with HNSW for cosine distance" in withTable {
    table =>
      withStore(table, dims = Some(3)) { store =>
        query(
          table,
          s"SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = '$table'::regclass AND attname = 'embedding'"
        ) shouldBe Some("vector(3)")
        query(
          table,
          s"SELECT indexdef FROM pg_indexes WHERE tablename = '$table' AND indexname = 'idx_${table}_embedding'"
        ).value should include("hnsw")
        store.store(mem("a", Array(1f, 0f, 0f))).isRight shouldBe true
        store.store(mem("b", Array(1f, 0f))).isLeft shouldBe true
      }
      // Opening again with the same dimension changes nothing
      withStore(table, dims = Some(3))(_.count().value shouldBe 1L)
  }

  it should "convert an older untyped column whose embeddings all have that dimension" in withTable { table =>
    withStore(table)(_.storeAll(Seq(mem("a", Array(1f, 0f, 0f)), mem("b", Array(0f, 1f, 0f)))).value)
    withStore(table, dims = Some(3))(_.count().value shouldBe 2L)
    query(
      table,
      s"SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = '$table'::regclass AND attname = 'embedding'"
    ) shouldBe Some("vector(3)")
  }

  it should "refuse to open over embeddings of another dimension, or a column typed with another" in withTable {
    table =>
      withStore(table)(_.storeAll(Seq(mem("a", Array(1f, 0f, 0f)), mem("b", Array(1f, 0f)))).value)
      open(table, dims = Some(3)).left.value.message should include("re-embed")
      withStore(table)(_.delete(MemoryId("b")).value)
      withStore(table, dims = Some(3))(_ => ())
      open(table, dims = Some(4)).left.value.message should include("vector(3)")
  }

  // Codex on #3289: the HNSW scan picks its candidates before the filter's condition applies (ef_search, 40)
  it should "return every match of a selective filter, though the index's nearest candidates are others" in
    withTable { table =>
      val service = new MemoryStoreContract.ContractEmbeddings
      service.define("query", Array(1f, 0f, 0f))
      withStore(table, dims = Some(3), service = Some(service)) { store =>
        store
          .storeAll((1 to 5000).map(i => mem(s"c$i", Array(1f, i / 100000f, 0f)).copy(content = s"crowd $i")))
          .value
        store.storeAll((1 to 5).map(i => mem(s"t$i", Array(0f, 1f, i.toFloat)).copy(content = s"target $i"))).value
        PostgresContractDb.execute(table, s"ANALYZE $table")
        val wanted = MemoryFilter.ContentContains("target", caseSensitive = true)
        store.search("query", 10, wanted).value.map(_.memory.id.value).sorted shouldBe (1 to 5).map(i => s"t$i")
        store.search("query", 3, wanted).value.size shouldBe 3
      }
    }

  it should "refuse a query of another dimension before it reaches the index" in withTable { table =>
    val service = new MemoryStoreContract.ContractEmbeddings
    service.define("query", Array(1f, 0f))
    withStore(table, dims = Some(3), service = Some(service)) { store =>
      store.store(mem("a", Array(1f, 0f, 0f))).value
      store.search("query", 5, MemoryFilter.All).left.value shouldBe a[ConfigurationError]
    }
  }

  "PostgresMemoryStore.reembedAll" should "re-embed the memories of another dimension, once each" in withTable {
    table =>
      val service = new MemoryStoreContract.ContractEmbeddings
      withStore(table, service = Some(service)) { store =>
        store.storeAll((1 to 5).map(i => mem(s"old$i", Array(1f, 0f)))).value
        store.store(mem("new", Array(0f, 0f, 1f))).value
        store.reembedAll(3, batchSize = 2).value shouldBe 5
        store.reembedAll(3).value shouldBe 0
        store.get(MemoryId("new")).value.flatMap(_.embedding).map(_.toSeq) shouldBe Some(Seq(0f, 0f, 1f))
        query(table, s"SELECT count(DISTINCT vector_dims(embedding)) FROM $table") shouldBe Some("1")
      }
  }
}
