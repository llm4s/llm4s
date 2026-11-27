package org.llm4s.agent.memory

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * The contract of a [[TransactionalMemoryStore]] (design §4.31): applied writes recorded with the write, fenced by
 * the token per scope, bounded by eviction in the same transaction. Mixed into one spec per store: this module's are
 * in `TransactionalMemoryStoreSpecs.scala`. It uses only ScalaTest and main classes, because `modules/it` compiles this
 * one file into its own tests (`Test / unmanagedSources`) for `PostgresMemoryStore` (#3217).
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

  it should "replace what it names, and report what it deleted" in withStore { s0 =>
    val a         = memory("a")
    val c         = memory("c")
    val (s1, _)   = applied(s0, key("add"), 1, Seq(a, c))
    val (s2, out) = applied(s1, key("swap"), 1, Seq(memory("b")), Seq(a.id, c.id, a.id))
    out.removed.toSet shouldBe Set(a.id, c.id)
    contents(s2) shouldBe Vector("b")
  }

  it should "write nothing for a replacement whose sources another writer already replaced" in withStore { s0 =>
    val a       = memory("a")
    val b       = memory("b")
    val (s1, _) = applied(s0, key("add"), 1, Seq(a, b))
    // two writers summarise the same pair; the first replaces it, the second finds it gone
    val (s2, first)  = applied(s1, key("first", scopeA), 1, Seq(memory("summary 1")), Seq(a.id, b.id))
    val (s3, second) = applied(s2, key("second", scopeB), 1, Seq(memory("summary 2")), Seq(a.id, b.id))
    first.added should have size 1
    second shouldBe AppliedWrite(Vector.empty, Vector.empty, 0)
    contents(s3) shouldBe Vector("summary 1")
    // and its replay returns that outcome
    applied(s3, key("second", scopeB), 1, Seq(memory("summary 2")), Seq(a.id, b.id))._2 shouldBe second
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

  // #3034: a writer bounds what it wrote, never what someone else stored in the same store
  it should "with an owner, count and evict only the owner's memories" in withStore { s0 =>
    val bounds = MemoryBounds(maxMemories = 2).withOwner("writer", "me")
    val mine   = (m: Memory) => m.withMetadata(Map("writer" -> "me"))
    val loaded = (1 to 5).map(i => memory(s"loaded $i", None, at = i.toLong))
    val s1     = s0.storeAll(loaded).value.asInstanceOf[TransactionalMemoryStore]
    val (s2, a) =
      applied(s1, key("a"), 1, Seq(mine(memory("mine 1", at = 10)), mine(memory("mine 2", at = 11))), Nil, bounds)
    a.evicted shouldBe 0
    val (s3, b) = applied(s2, key("b"), 1, Seq(mine(memory("mine 3", at = 12))), Nil, bounds)
    b.evicted shouldBe 1
    contents(s3) shouldBe (loaded.map(_.content) ++ Vector("mine 2", "mine 3")).sorted
    bounds.counts(Map("writer" -> "me")) shouldBe true
    bounds.counts(Map("writer" -> "you")) shouldBe false
    MemoryBounds().counts(Map.empty) shouldBe true
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
