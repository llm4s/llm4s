package org.llm4s.agent.memory

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * The contract of a [[TransactionalMemoryStore]] (design §4.31): applied writes recorded with the write, fenced by
 * the token per scope, bounded by eviction in the same transaction. Mixed into one spec per store.
 */
trait TransactionalMemoryStoreContract extends AnyFlatSpecLike with Matchers with EitherValues {

  /** A fresh, empty store. */
  protected def newStore(): TransactionalMemoryStore

  /** Names the store in the suite's output. */
  protected def storeName: String

  /** Runs `body` on a fresh store, then releases it. */
  protected def withStore[A](body: TransactionalMemoryStore => A): A = body(newStore())

  private val scopeA = "scope-a"
  private val scopeB = "scope-b"
  private val big    = MemoryBounds(1000, 1L << 30)

  private def key(value: String, scope: String = scopeA) = AppliedWriteKey(scope, value)

  private def memory(content: String, importance: Option[Double] = None, at: Long = 0L): Memory =
    Memory(
      MemoryId.generate(),
      content,
      MemoryType.Conversation,
      timestamp = Instant.ofEpochSecond(1_700_000_000L + at),
      importance = importance
    )

  private def contents(store: MemoryStore): Vector[String] =
    store.recall(MemoryFilter.All, Int.MaxValue).value.map(_.content).toVector.sorted

  private def applied(
    store: TransactionalMemoryStore,
    k: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId] = Nil,
    bounds: MemoryBounds = big
  ): (TransactionalMemoryStore, AppliedWrite) = store.applyAtomically(k, token, add, remove, bounds).value

  behavior.of(s"A transactional memory store ($storeName)")

  it should "apply a write and record its outcome" in withStore { s0 =>
    val m         = memory("one")
    val (s1, out) = applied(s0, key("k"), 1, Seq(m))
    out shouldBe AppliedWrite(Vector(m.id), Vector.empty, 0)
    contents(s1) shouldBe Vector("one")
    s1.appliedWrite(key("k"), 1).value._2 shouldBe Some(out)
    s1.appliedWriteCount.value shouldBe 1
  }

  it should "return the recorded outcome for a key already applied, and write nothing" in withStore { s0 =>
    val (s1, first)  = applied(s0, key("k"), 1, Seq(memory("one")))
    val (s2, second) = applied(s1, key("k"), 1, Seq(memory("other")), Nil)
    second shouldBe first
    contents(s2) shouldBe Vector("one")
  }

  it should "never re-insert a memory deleted after its write was applied" in withStore { s0 =>
    val m         = memory("one")
    val (s1, out) = applied(s0, key("record"), 1, Seq(m))
    // another writer consolidates it away
    val summary     = memory("summary")
    val (s2, _)     = applied(s1, key("consolidate", scopeB), 1, Seq(summary), Seq(m.id))
    val (s3, again) = applied(s2, key("record"), 1, Seq(m))
    again shouldBe out
    contents(s3) shouldBe Vector("summary")
  }

  it should "delete only what is there, and report what it deleted" in withStore { s0 =>
    val a         = memory("a")
    val (s1, _)   = applied(s0, key("add"), 1, Seq(a))
    val (s2, out) = applied(s1, key("swap"), 1, Seq(memory("b")), Seq(a.id, MemoryId("absent")))
    out.removed shouldBe Vector(a.id)
    contents(s2) shouldBe Vector("b")
  }

  it should "keep two keys of one write apart" in withStore { s0 =>
    val (s1, _) = applied(s0, key("one"), 1, Seq(memory("one")))
    val (s2, _) = applied(s1, key("two"), 1, Seq(memory("two")))
    contents(s2) shouldBe Vector("one", "two")
    s2.appliedWriteCount.value shouldBe 2
  }

  it should "forget an applied key, so the key applies again" in withStore { s0 =>
    val (s1, _) = applied(s0, key("k"), 1, Seq(memory("one")))
    val s2      = s1.forgetApplied(key("k"), 1).value
    s2.appliedWriteCount.value shouldBe 0
    s2.appliedWrite(key("k"), 1).value._2 shouldBe None
    val (s3, _) = applied(s2, key("k"), 1, Seq(memory("two")))
    contents(s3) shouldBe Vector("one", "two")
    s3.forgetApplied(key("never"), 1).isRight shouldBe true
  }

  it should "refuse every operation with a token older than the scope's newest, changing nothing" in withStore { s0 =>
    val (s1, _) = applied(s0, key("k"), 5, Seq(memory("one")))
    s1.applyAtomically(key("other"), 4, Seq(memory("stale")), Nil, big).isLeft shouldBe true
    s1.applyAtomically(key("k"), 4, Nil, Nil, big).isLeft shouldBe true
    s1.appliedWrite(key("k"), 4).isLeft shouldBe true
    s1.forgetApplied(key("k"), 4).isLeft shouldBe true
    contents(s1) shouldBe Vector("one")
    s1.appliedWriteCount.value shouldBe 1
    // the same or a newer token goes ahead, and a newer one fences the older out from then on
    val (s2, _) = applied(s1, key("other"), 5, Seq(memory("two")))
    val s3      = s2.forgetApplied(key("k"), 7).value
    s3.appliedWrite(key("other"), 6).isLeft shouldBe true
    // another scope keeps its own newest token
    val (s4, _) = applied(s3, key("b", scopeB), 1, Seq(memory("three")))
    contents(s4) shouldBe Vector("one", "three", "two")
  }

  it should "fence a read: a token that read the outcome raises the scope's newest" in withStore { s0 =>
    val (s1, _) = applied(s0, key("k"), 1, Seq(memory("one")))
    val s2      = s1.appliedWrite(key("k"), 3).value._1
    s2.forgetApplied(key("k"), 2).isLeft shouldBe true
  }

  it should "evict beyond maxMemories in the same write: lowest importance first, then oldest" in withStore { s0 =>
    val bounds  = MemoryBounds(maxMemories = 3)
    val low     = memory("low", Some(0.1), at = 10)
    val old     = memory("old", Some(0.5), at = 1)
    val none    = memory("none", None, at = 5) // counts as 0.5, newer than `old`
    val high    = memory("high", Some(0.9), at = 0)
    val (s1, a) = applied(s0, key("a"), 1, Seq(low, old, none, high), Nil, bounds)
    a.evicted shouldBe 1
    contents(s1) shouldBe Vector("high", "none", "old")
    val (s2, b) = applied(s1, key("b"), 1, Seq(memory("new", Some(0.5), at = 20)), Nil, bounds)
    b.evicted shouldBe 1
    contents(s2) shouldBe Vector("high", "new", "none")
    s2.count().value shouldBe 3
  }

  it should "evict beyond maxBytes" in withStore { s0 =>
    val bounds  = MemoryBounds(maxMemories = 100, maxBytes = 25)
    val (s1, _) = applied(s0, key("a"), 1, Seq(memory("0123456789", at = 1), memory("abcdefghij", at = 2)), Nil, bounds)
    contents(s1) shouldBe Vector("0123456789", "abcdefghij")
    val (s2, b) = applied(s1, key("b"), 1, Seq(memory("ABCDEFGHIJ", at = 3)), Nil, bounds)
    b.evicted shouldBe 1
    contents(s2) shouldBe Vector("ABCDEFGHIJ", "abcdefghij")
  }

  it should "keep applied keys when the store is cleared" in withStore { s0 =>
    val (s1, out) = applied(s0, key("k"), 1, Seq(memory("one")))
    val s2        = s1.clear().value.asInstanceOf[TransactionalMemoryStore]
    s2.appliedWrite(key("k"), 1).value._2 shouldBe Some(out)
    s2.applyAtomically(key("k"), 0, Nil, Nil, big).isLeft shouldBe true
  }

  it should "replace atomically: the added memories in, the removed ones out" in withStore { s0 =>
    val a  = memory("a")
    val b  = memory("b")
    val s1 = s0.storeAll(Seq(a, b)).value
    val s2 = s1.replace(Seq(memory("ab")), Seq(a.id, b.id)).value
    contents(s2) shouldBe Vector("ab")
  }

  it should "store a repeated id once, as its last occurrence" in withStore { s0 =>
    val m       = memory("first")
    val (s1, a) = applied(s0, key("k"), 1, Seq(m, m.copy(content = "second")))
    a.added shouldBe Vector(m.id)
    contents(s1) shouldBe Vector("second")
  }

}

object TransactionalMemoryStoreContract {

  /** `body`, then `cleanup` whatever `body` did; `body`'s throw, if any, is rethrown. */
  def after[A](cleanup: => Unit)(body: => A): A = {
    val result = scala.util.Try(body)
    cleanup
    result.get
  }
}

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
