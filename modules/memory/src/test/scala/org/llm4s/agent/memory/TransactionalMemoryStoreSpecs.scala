package org.llm4s.agent.memory

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers

// `TransactionalMemoryStoreContract` over this module's stores. The contract has a file of its own because `modules/it`
// compiles that one file into its own tests for `PostgresMemoryStore` (#3217).

/** The contract over [[InMemoryStore]]. */
class InMemoryTransactionalMemoryStoreSpec extends TransactionalMemoryStoreContract {
  protected def storeName: String                    = "InMemoryStore"
  protected def newStore(): TransactionalMemoryStore = InMemoryStore.empty

  "An InMemoryStore" should "leave the store it was applied to unchanged" in {
    val s0      = InMemoryStore.empty
    val (s1, _) = s0.applyAtomically(AppliedWriteKey("s", "k"), 1, Seq(Memory.userFact("x")), Nil, MemoryBounds()).value
    s0.size shouldBe 0
    s0.appliedWriteCount.value shouldBe 0
    s1.appliedWriteCount.value shouldBe 1
  }
}

/** The applied-write vocabulary on its own. */
class AppliedWriteSpec extends org.scalatest.flatspec.AnyFlatSpec with Matchers with EitherValues {

  "AppliedWrite's JSON" should "round-trip, and refuse a newer format or an unreadable value" in {
    val write = AppliedWrite(Vector(MemoryId("a")), Vector(MemoryId("b")), 2)
    AppliedWrite.fromJson(AppliedWrite.toJson(write)).value shouldBe write
    val newer = AppliedWrite.toJson(write)
    newer("format") = AppliedWrite.Format + 1
    AppliedWrite.fromJson(newer).left.value.message should include("newer")
    AppliedWrite.fromJson(ujson.Str("nonsense")).isLeft shouldBe true
  }

  "MemoryBounds" should "refuse limits below 1, and size a memory by its content and metadata" in {
    an[IllegalArgumentException] should be thrownBy MemoryBounds(maxMemories = 0)
    an[IllegalArgumentException] should be thrownBy MemoryBounds(maxBytes = 0)
    MemoryBounds().withMaxMemories(3).withMaxBytes(9) shouldBe MemoryBounds(3, 9)
    MemoryBounds().withOwner("w", "me").withMaxMemories(3).withMaxBytes(9).owner shouldBe
      Some(MemoryBounds.Owner("w", "me"))
    MemoryBounds.sizeOf(Memory.userFact("é").withMetadata(Map("k" -> "vv"))) shouldBe
      2 + MemoryBounds.sizeOf("", Memory.userFact("é").withMetadata(Map("k" -> "vv")).metadata)
  }
}

/** The contract over [[SQLiteMemoryStore]], one in-memory database per case. */
class SqliteTransactionalMemoryStoreSpec extends TransactionalMemoryStoreContract {
  protected def storeName: String = "SQLiteMemoryStore"
  protected def newStore(): TransactionalMemoryStore =
    SQLiteMemoryStore.inMemory().fold(e => fail(e.message), identity)

  override protected def withStore[A](body: TransactionalMemoryStore => A): A = {
    val store = SQLiteMemoryStore.inMemory().fold(e => fail(e.message), identity)
    TransactionalMemoryStoreContract.after(store.close())(body(store))
  }

  "A SQLiteMemoryStore file" should "keep its applied writes and tokens across a reopen" in {
    val file = java.nio.file.Files.createTempFile("llm4s-applied", ".db")
    TransactionalMemoryStoreContract.after(java.nio.file.Files.deleteIfExists(file): Unit) {
      val first = SQLiteMemoryStore(file.toString).value
      val k     = AppliedWriteKey("s", "k")
      val out   = first.applyAtomically(k, 5, Seq(Memory.userFact("x")), Nil, MemoryBounds()).value._2
      first.close()
      val second = SQLiteMemoryStore(file.toString).value
      TransactionalMemoryStoreContract.after(second.close()) {
        second.appliedWrite(k, 5).value._2 shouldBe Some(out)
        second.forgetApplied(k, 4).isLeft shouldBe true
      }
    }
  }

  it should "open a version-1 file, without the applied-write tables, and gain them" in {
    val file = java.nio.file.Files.createTempFile("llm4s-v1", ".db")
    TransactionalMemoryStoreContract.after(java.nio.file.Files.deleteIfExists(file): Unit) {
      val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${file.toString}")
      TransactionalMemoryStoreContract.after(conn.close()) {
        val st = conn.createStatement()
        st.executeUpdate(
          "CREATE TABLE memories (id TEXT PRIMARY KEY, content TEXT NOT NULL, memory_type TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL, importance REAL, conversation_id TEXT, entity_id TEXT, source TEXT, " +
            "metadata_json TEXT DEFAULT '{}', embedding_blob BLOB)"
        )
        st.executeUpdate("CREATE VIRTUAL TABLE memories_fts USING fts5(id UNINDEXED, content)")
        st.executeUpdate("CREATE TABLE schema_version (version INTEGER PRIMARY KEY)")
        st.executeUpdate("INSERT INTO schema_version (version) VALUES (1)")
        st.executeUpdate(
          "INSERT INTO memories (id, content, memory_type, timestamp) VALUES ('old', 'kept', 'conversation', 0)"
        )
        st.close()
      }
      val store = SQLiteMemoryStore(file.toString).value
      TransactionalMemoryStoreContract.after(store.close()) {
        store.get(MemoryId("old")).value.map(_.content) shouldBe Some("kept")
        store
          .applyAtomically(AppliedWriteKey("s", "k"), 1, Seq(Memory.userFact("x")), Nil, MemoryBounds())
          .isRight shouldBe true
        store.appliedWriteCount.value shouldBe 1
      }
    }
  }
}
