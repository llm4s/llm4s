package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{
  EffectKey,
  FencingToken,
  ReplayGuard,
  ReplaySupport,
  RunConfig,
  RunContext,
  TenantId,
  ThreadId
}
import org.llm4s.agent.harness.*
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Using

/**
 * The contract every [[org.llm4s.agent.harness.LongTermStore]] must meet (design §4.25), as a ScalaTest suite: mix
 * it into a spec, implement [[newStore]] and [[appliedKeys]], and every case below runs against your store:
 *
 * {{{
 * class InMemoryLongTermStoreContractSpec extends AnyFlatSpec with LongTermStoreContract:
 *   protected def newStore(limits: StoreLimits): LongTermStore = InMemoryLongTermStore(limits)
 *   protected def appliedKeys(store: LongTermStore): Int = store.asInstanceOf[InMemoryLongTermStore].appliedKeys
 * }}}
 *
 * The cases are the ones the `LongTermStore` Scaladoc states:
 *
 *  - '''Items.''' `put`, `get`, `list` and `delete` through the replay guard; a value read back exactly as written,
 *    whatever its strings hold; listings by literal prefix, in key order, cut at `limit` and saying so; namespaces
 *    kept apart, the tenant included; every value copied in and out; `updated` never going back.
 *  - '''Bounds''' (rule 12). An over-long key, prefix, query or namespace refused first; one item's bytes, and one
 *    namespace's item count and bytes, checked in the transaction, exactly at the limit, counted in UTF-8, an
 *    overwrite counted once; a value's and an outcome's depth; an outcome's size.
 *  - '''`modify`.''' The change decided against a [[org.llm4s.agent.harness.StoreView StoreView]] of its namespace;
 *    a refused change writing and recording nothing; `(effect, outcome)` recorded with the write, and a second
 *    `modify` under the same key answered with the recorded outcome, its change not run, whatever was written since;
 *    a change that calls back into the store refused.
 *  - '''Fencing''' (`AppliedKeys`). A token older than the newest seen for the key's scope refused by `modify` and
 *    `forget` alike, changing nothing, an applied key included; a scope per thread; the newest token kept even
 *    when the change it carried was refused.
 *  - '''Concurrency.''' Increments from many threads all counted, and a namespace's item limit exact under
 *    concurrent writers, through two instances of the store when it has them ([[sibling]]).
 *
 * How a store matches a `search` is its own to document, so the substring cases run only for a store that matches
 * as the shipped ones do ([[matchesSubstrings]]). Replay across a crash is [[ReplayContract]]'s: run it too, with
 * [[LongTermStoreContract.CounterSubject]] as the subject.
 */
@Experimental
trait LongTermStoreContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:

  /** A fresh, empty store with `limits`. */
  protected def newStore(limits: StoreLimits): LongTermStore

  /** How many applied keys `store` holds now, read from its storage. */
  protected def appliedKeys(store: LongTermStore): Int

  /**
   * Another instance over `store`'s storage, as a second machine opens it, with the same limits; `store` itself for
   * a store that has no other.
   */
  protected def sibling(store: LongTermStore): LongTermStore = store

  /**
   * Whether `search` matches as the shipped stores do: the query, case-insensitively, as a substring of the key or
   * of any string in the value, in key order. A store that documents another match overrides this to `false`.
   */
  protected def matchesSubstrings: Boolean = true

  /** How long a case waits for its concurrent writers before it fails rather than hangs. */
  protected def contractWait: FiniteDuration = 60.seconds

  import LongTermStoreContract.*

  private def store(limits: StoreLimits = StoreLimits.default): LongTermStore = newStore(limits)

  private val ns = Namespace(None, Vector("notes"))

  /** `change` under a key of its own, as one unguarded effect. */
  private def change[A: upickle.default.ReadWriter](s: LongTermStore, key: String, in: Namespace = ns)(
    decide: StoreView => Result[StoreChange[A]]
  ): Result[A] =
    s.modify[A](in, key, effectKey(), FencingToken(0))(decide)

  private def put(s: LongTermStore, key: String, value: ujson.Value, in: Namespace = ns): Result[Unit] =
    change[Unit](s, key, in)(_ => Right(StoreChange.Put(value, ())))

  private def text(s: LongTermStore, key: String, in: Namespace = ns): Option[String] =
    s.get(in, key).value.map(_.value.str)

  "A long-term store" should "put, get, list by prefix and delete through the guard, leaving no applied key" in {
    val s = store()
    s.replaySupport shouldBe ReplaySupport.Transactional
    s.put(ns, "a/1", ujson.Obj("text" -> "Scala is nice"), call()).value shouldBe true
    s.put(ns, "a/1", ujson.Obj("text" -> "Scala is great"), call()).value shouldBe false
    s.put(ns, "b/1", ujson.Str("other"), call()).value shouldBe true
    s.get(ns, "a/1").value.map(_.value("text").str) shouldBe Some("Scala is great")
    s.get(ns, "missing").value shouldBe None
    s.list(ns, "a/", 10).value shouldBe Listing(
      Vector(StoreEntry("a/1", s.get(ns, "a/1").value.value)),
      truncated = false
    )
    s.delete(ns, "a/1", call()).value shouldBe true
    s.delete(ns, "a/1", call()).value shouldBe false
    s.get(ns, "a/1").value shouldBe None
    s.list(ns, "", 10).value.items.map(_.key) shouldBe Vector("b/1")
    appliedKeys(s) shouldBe 0
  }

  it should "return a value exactly as it was written, whatever its strings hold" in {
    val s = store()
    val value = ujson.Obj(
      "text"    -> "naïve 😀 \"quoted\" back\\slash\nline\ttab \u0000 nul  ",
      "é"       -> ujson.Arr(1, 1.5, -0.25, 1e300, 12345678901234L, true, false, ujson.Null),
      "nested"  -> ujson.Obj("empty" -> ujson.Obj(), "list" -> ujson.Arr(), "s" -> ""),
      "percent" -> "100% _sure_"
    )
    put(s, "k é/😀 %_'\"", value).value
    s.get(ns, "k é/😀 %_'\"").value.value.value shouldBe value
    s.list(ns, "k ", 10).value.items.map(_.item.value) shouldBe Vector(value)
    // a key is matched exactly: by case, and to its last character
    s.get(ns, "K é/😀 %_'\"").value shouldBe None
    s.get(ns, "k é/😀 %_'").value shouldBe None
  }

  it should "list by literal prefix in key order, cut at the limit and say so" in {
    val s = store()
    Vector("a/2", "b", "a/10", "a/1", "a%b", "a_b", "axb", "A/1").foreach(k => put(s, k, ujson.Str(k)).value)
    s.list(ns, "a/", 10).value shouldBe Listing(
      Vector("a/1", "a/10", "a/2").map(k => StoreEntry(k, s.get(ns, k).value.value)),
      truncated = false
    )
    s.list(ns, "", 100).value.items.map(_.key) shouldBe Vector("A/1", "a%b", "a/1", "a/10", "a/2", "a_b", "axb", "b")
    // a prefix is text, never a pattern
    s.list(ns, "a_", 10).value.items.map(_.key) shouldBe Vector("a_b")
    s.list(ns, "a%", 10).value.items.map(_.key) shouldBe Vector("a%b")
    s.list(ns, "nothing", 10).value shouldBe Listing(Vector.empty, truncated = false)
    val cut = s.list(ns, "a/", 2).value
    cut.items.map(_.key) shouldBe Vector("a/1", "a/10")
    cut.truncated shouldBe true
    s.list(ns, "a/", 3).value.truncated shouldBe false
    s.list(ns, "", Int.MaxValue).value.items should have size 8
    s.list(ns, "a/", 0).left.value shouldBe a[BackendRefused]
    s.search(ns, "a", 0).left.value shouldBe a[BackendRefused]
  }

  it should "search only its namespace, cut at the limit, handing out copies" in {
    val s     = store()
    val other = Namespace(None, Vector("elsewhere"))
    put(s, "topic", ujson.Obj("text" -> "the same words")).value
    put(s, "topic", ujson.Obj("text" -> "the same words"), other).value
    put(s, "topic-2", ujson.Obj("text" -> "the same words again"), other).value
    val here = s.search(ns, "topic", 10).value
    here.items.map(_.key) shouldBe Vector("topic")
    here.truncated shouldBe false
    here.items.head.item.value("text") = "changed"
    s.get(ns, "topic").value.value.value("text").str shouldBe "the same words"
    val cut = s.search(other, "topic", 1).value
    cut.items should have size 1
    cut.truncated shouldBe true
    s.search(Namespace(None, Vector("empty")), "topic", 10).value shouldBe Listing(Vector.empty, truncated = false)
  }

  it should "match a search case-insensitively against keys and every string of a value, in key order" in {
    assume(matchesSubstrings, "the store documents another match")
    val s = store()
    put(s, "b/Title", ujson.Obj("text" -> "Scala is GREAT", "n" -> 42)).value
    put(s, "a/deep", ujson.Obj("tags" -> ujson.Arr("x", ujson.Obj("inner" -> "a greater good")))).value
    put(s, "c/none", ujson.Obj("great" -> "the name of a field is not searched", "n" -> 42)).value
    put(s, "d/city", ujson.Str("ISTANBUL")).value
    s.search(ns, "great", 10).value.items.map(_.key) shouldBe Vector("a/deep", "b/Title")
    s.search(ns, "TITLE", 10).value.items.map(_.key) shouldBe Vector("b/Title")
    s.search(ns, "42", 10).value.items shouldBe empty
    // whatever the JVM's locale: a Turkish one lower-cases `I` to a dotless `i`
    val previous = java.util.Locale.getDefault
    val hits = Using.resource(new AutoCloseable { def close(): Unit = java.util.Locale.setDefault(previous) }) { _ =>
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
      s.search(ns, "istanbul", 10)
    }
    hits.value.items.map(_.key) shouldBe Vector("d/city")
    // a query is text, never a pattern
    s.search(ns, "%", 10).value.items shouldBe empty
    s.search(ns, "", 10).value.items should have size 4
  }

  it should "keep namespaces apart, the tenant included" in {
    val s    = store()
    val nsA  = Namespace(Some(TenantId("a")), Vector("notes"))
    val nsB  = Namespace(Some(TenantId("b")), Vector("notes"))
    val deep = Namespace(Some(TenantId("a")), Vector("notes", "sub"))
    // a tenant named like a path segment, and a path split differently, are other namespaces
    val lookalikes = Vector(Namespace(None, Vector("a", "notes")), Namespace(Some(TenantId("a")), Vector("not", "es")))
    put(s, "k", ujson.Str("a's"), nsA).value
    (Vector(ns, nsB, deep) ++ lookalikes).foreach { other =>
      s.get(other, "k").value shouldBe None
      s.list(other, "", 10).value.items shouldBe empty
      s.search(other, "k", 10).value.items shouldBe empty
    }
    put(s, "k", ujson.Str("b's"), nsB).value
    put(s, "k", ujson.Str("none's")).value
    text(s, "k", nsA) shouldBe Some("a's")
    change[Boolean](s, "k", nsB)(view => Right(StoreChange.Remove(view.current.isDefined))).value shouldBe true
    text(s, "k", nsA) shouldBe Some("a's")
    text(s, "k") shouldBe Some("none's")
    // the run's tenant is the namespace's only when the caller says so: put and delete take the namespace given
    s.put(nsA, "guarded", ujson.Str("x"), call(tenant = Some("a"))).value shouldBe true
    s.get(nsB, "guarded").value shouldBe None
  }

  it should "refuse an over-long key, prefix, query or namespace first, quoting only a prefix" in {
    val s    = store()
    val huge = "k" * 10000000
    def short(r: Result[?]): String =
      val e = r.left.value
      e shouldBe a[BackendRefused]
      e.message.length should be < 400
      e.message
    short(s.put(ns, huge, ujson.Str("v"), call())) should include("longer than 1024 bytes")
    short(s.delete(ns, huge, call())) should include("longer than 1024 bytes")
    short(s.get(ns, huge)) should include("longer than 1024 bytes")
    short(s.list(ns, huge, 10)) should include("longer than 1024 bytes")
    short(s.search(ns, huge, 10)) should include("longer than 1024 bytes")
    short(change[Unit](s, huge)(_ => Right(StoreChange.Keep(())))) should include("longer than 1024 bytes")
    // bytes, not characters: 400 three-byte characters are over, 1024 one-byte ones are not
    short(s.get(ns, "€" * 400)) should include("longer than 1024 bytes")
    put(s, "k" * LongTermStore.MaxKeyBytes, ujson.Str("v")).value
    text(s, "k" * LongTermStore.MaxKeyBytes) shouldBe Some("v")
    val wide = Namespace(None, Vector.fill(2000)("n"))
    short(s.put(wide, "k", ujson.Str("v"), call())) should include("namespace")
    short(s.get(wide, "k")) should include("namespace")
    short(s.list(wide, "", 10)) should include("namespace")
    // empty segments have no characters, but a million of them would render a million-element array
    val many = Namespace(None, Vector.fill(1000000)(""))
    short(s.put(many, "k", ujson.Str("v"), call())) should include("segments")
    short(s.delete(many, "k", call())) should include("segments")
    // the longest namespace there is holds items like any other
    val longest = Namespace(Some(TenantId("t" * 512)), Vector("p" * 256, "q" * 256))
    put(s, "k", ujson.Str("far"), longest).value
    text(s, "k", longest) shouldBe Some("far")
    appliedKeys(s) shouldBe 2
  }

  it should "bound items and namespaces inside the transaction, as refusals" in {
    val s = store(StoreLimits(maxItemBytes = 20, maxItems = 2, maxBytes = 30))
    s.put(ns, "k", ujson.Str("x" * 30), call()).left.value.message should include("over the limit of 20")
    s.put(ns, "a", ujson.Str("0123456789"), call()).value // 1 + 12
    s.put(ns, "b", ujson.Str("0123456789"), call()).value // 26
    val third = s.put(ns, "c", ujson.Str(""), call()).left.value
    third shouldBe a[BackendRefused]
    third.message should include("3 items")
    s.put(ns, "b", ujson.Str("0123456789012345"), call()).left.value.message should include(
      "bytes, over its limit of 30"
    )
    s.get(ns, "c").value shouldBe None
    text(s, "b") shouldBe Some("0123456789")
    // an overwrite is counted once, and exactly to the limit: 13 + (1 + 16) is 30
    s.put(ns, "b", ujson.Str("01234567890123"), call()).value shouldBe false
    // a delete frees its item's room
    s.delete(ns, "a", call()).value shouldBe true
    s.put(ns, "c", ujson.Str(""), call()).value shouldBe true
    // the bounds are one namespace's: another starts empty
    val other = Namespace(None, Vector("other"))
    s.put(other, "a", ujson.Str("0123456789"), call()).value shouldBe true
    s.put(other, "b", ujson.Str("0123456789"), call()).value shouldBe true
    appliedKeys(s) shouldBe 0
  }

  it should "count an item in UTF-8 bytes, its key and its encoded value, exactly at the limit" in {
    // the key is 2 bytes; the value 2 quotes and 2 + 3 + 4 bytes of text, and an escape's 2
    val value = ujson.Str("é€😀\n")
    put(store(StoreLimits(maxItemBytes = 15)), "é", value).value
    put(store(StoreLimits(maxItemBytes = 14)), "é", value).left.value.message should include(
      "over the limit of 14"
    )
    val bounded = store(StoreLimits(maxItemBytes = 15, maxBytes = 29))
    put(bounded, "é", value).value
    put(bounded, "è", value).left.value.message should include("30 bytes, over its limit of 29")
    put(bounded, "e", value).value
  }

  it should "refuse a value nested deeper than MaxDepth, and a wide one by its size, without overflowing the stack" in {
    val s    = store()
    val deep = (1 to 100000).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    // 100 000 levels are over the size cap too, which is counted first, iteratively: refused, no overflow
    put(s, "deep", deep).left.value.message should include("over the limit")
    val wide = ujson.Arr.from(Iterator.fill(2000000)(ujson.Null))
    put(s, "wide", wide).left.value.message should include("over the limit")
    val deeper  = (1 to LongTermStore.MaxDepth + 1).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    val refused = put(s, "deeper", deeper).left.value
    refused shouldBe a[BackendRefused]
    refused.message should include("nested deeper")
    val ok = (1 to LongTermStore.MaxDepth - 1).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    put(s, "ok", ok).value
    s.get(ns, "ok").value.value.value shouldBe ok
    s.list(ns, "", 10).value.items.map(_.key) shouldBe Vector("ok")
    appliedKeys(s) shouldBe 1
  }

  it should "refuse a key that is not valid Unicode, writing and recording nothing" in {
    val s = store()
    // built from the code unit: scalafix's parser rejects an unpaired surrogate escape in a literal
    val refused = s.put(ns, "bad" + 0xd800.toChar, ujson.Str("x"), call()).left.value
    refused shouldBe a[BackendRefused]
    refused.message should include("not valid Unicode")
    s.list(ns, "", 10).value.items shouldBe empty
    appliedKeys(s) shouldBe 0
  }

  it should "copy values in and out, so no caller can change what it stores" in {
    val s     = store()
    val input = ujson.Obj("n" -> 1)
    put(s, "k", input).value
    input("n") = 2
    s.get(ns, "k").value.value.value("n").num shouldBe 1
    s.get(ns, "k").value.value.value("n") = 3
    s.list(ns, "", 10).value.items.head.item.value("n") = 4
    change[Unit](s, "k") { view =>
      view.current.value.value("n") = 6
      view.get("k").value.value.value("n") = 7
      view.withPrefix("", 10).value.head.item.value("n") = 8
      Left(BackendRefused("put", "k", "no"))
    }.left.value shouldBe a[BackendRefused]
    s.get(ns, "k").value.value.value("n").num shouldBe 1
  }

  it should "stamp an item when it is written, and never earlier than the item it replaces" in {
    val s = store()
    put(s, "k", ujson.Str("one")).value
    val first = s.get(ns, "k").value.value.updated
    s.list(ns, "", 10).value.items.head.item.updated shouldBe first
    change[Unit](s, "k")(_ => Right(StoreChange.Keep(()))).value
    s.get(ns, "k").value.value.updated shouldBe first
    put(s, "k", ujson.Str("two")).value
    s.get(ns, "k").value.value.updated should be >= first
  }

  "LongTermStore.modify" should "decide its change against the item and the rest of its namespace" in {
    val s = store()
    Vector("dir/a", "dir/b", "dir/c", "other").foreach(k => put(s, k, ujson.Str(k)).value)
    put(s, "dir/a", ujson.Str("elsewhere"), Namespace(None, Vector("elsewhere"))).value
    val seen = change[Vector[String]](s, "dir/b") { view =>
      for
        other  <- view.get("other")
        absent <- view.get("missing")
        under  <- view.withPrefix("dir/", 2)
        all    <- view.withPrefix("", 100)
      yield StoreChange.Put(
        ujson.Str("changed"),
        Vector(
          view.current.value.value.str,
          other.value.value.str,
          absent.toString,
          under.map(_.key).mkString(","),
          all.map(e => s"${e.key}=${e.item.value.str}").mkString(",")
        )
      )
    }
    seen.value shouldBe Vector(
      "dir/b",
      "other",
      "None",
      "dir/a,dir/b",
      "dir/a=dir/a,dir/b=dir/b,dir/c=dir/c,other=other"
    )
    text(s, "dir/b") shouldBe Some("changed")
    change[Boolean](s, "dir/b")(view => Right(StoreChange.Remove(view.current.isDefined))).value shouldBe true
    s.get(ns, "dir/b").value shouldBe None
    change[Boolean](s, "new")(view => Right(StoreChange.Keep(view.current.isDefined))).value shouldBe false
    s.get(ns, "new").value shouldBe None
  }

  it should "return a recorded outcome for an applied key, writing nothing, even after another writer" in {
    val s    = store()
    val key  = effectKey()
    val runs = new AtomicInteger()
    def decide(view: StoreView) =
      runs.incrementAndGet()
      Right(StoreChange.Put(ujson.Str("first"), view.current.isEmpty))
    s.modify[Boolean](ns, "k", key, FencingToken(0))(decide).value shouldBe true
    val stamped = s.get(ns, "k").value.value.updated
    appliedKeys(s) shouldBe 1
    // answered before anything is read again: the item as it was, and after another writer changed it
    s.modify[Boolean](ns, "k", key, FencingToken(0))(decide).value shouldBe true
    s.get(ns, "k").value.value.updated shouldBe stamped
    s.put(ns, "k", ujson.Str("someone else"), call()).value shouldBe false
    s.modify[Boolean](ns, "k", key, FencingToken(0))(decide).value shouldBe true
    // and through another instance of the store
    sibling(s).modify[Boolean](ns, "k", key, FencingToken(0))(decide).value shouldBe true
    runs.get shouldBe 1
    text(s, "k") shouldBe Some("someone else")
    appliedKeys(s) shouldBe 1
    sibling(s).forget(key, FencingToken(0)).value
    appliedKeys(s) shouldBe 0
    // forgetting a key that is not there is no error, and a forgotten key's change runs again
    s.forget(key, FencingToken(0)).value
    s.modify[Boolean](ns, "k", key, FencingToken(0))(decide).value shouldBe false
    runs.get shouldBe 2
    text(s, "k") shouldBe Some("first")
  }

  it should "record nothing, and write nothing, for a refused change or one over a bound" in {
    val s = store(StoreLimits(maxItems = 1))
    put(s, "held", ujson.Str("kept")).value
    val before = appliedKeys(s)
    change[Boolean](s, "held")(_ => Left(BackendRefused("put", "held", "no"))).left.value shouldBe a[BackendRefused]
    change[Boolean](s, "second")(_ => Right(StoreChange.Put(ujson.Str("x"), true))).left.value shouldBe a[
      BackendRefused
    ]
    // an outcome over the replay cap refuses the write it came with
    val over = change[String](s, "held")(_ => Right(StoreChange.Put(ujson.Str("replaced"), "x" * (2 << 20)))).left.value
    over shouldBe a[ValidationError]
    over.message should include("replay cap")
    text(s, "held") shouldBe Some("kept")
    s.get(ns, "second").value shouldBe None
    appliedKeys(s) shouldBe before
  }

  it should "keep a recorded outcome its caller cannot change, and bound an outcome's depth" in {
    val s     = store()
    val key   = effectKey()
    def run() = s.modify[ujson.Value](ns, "k", key, FencingToken(0))(_ => Right(StoreChange.Keep(ujson.Obj("n" -> 1))))
    run().value("n") = 2
    run().value("n") = 3
    run().value("n").num shouldBe 1
    val deep = (1 to 100000).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    change[ujson.Value](s, "d")(_ => Right(StoreChange.Keep(deep))).left.value.message should include("nested deeper")
    // an outcome is recorded exactly, whatever its strings hold
    val odd     = ujson.Obj("s" -> "é 😀 \u0000 \"q\"", "n" -> 1.5)
    val oddKey  = effectKey()
    def odder() = s.modify[ujson.Value](ns, "odd", oddKey, FencingToken(0))(_ => Right(StoreChange.Keep(odd)))
    odder().value shouldBe odd
    odder().value shouldBe odd
  }

  it should "record the outcome its codec emitted once, whatever a second run would emit" in {
    final case class Shifty(v: String)
    val calls = new AtomicInteger()
    given upickle.default.ReadWriter[Shifty] = upickle.default
      .readwriter[ujson.Value]
      .bimap[Shifty](
        s => if calls.incrementAndGet() == 1 then ujson.Str(s.v) else ujson.Str("x" * 2000000),
        j => Shifty(j.str)
      )
    val s   = store()
    val key = effectKey()
    s.modify[Shifty](ns, "k", key, FencingToken(0))(_ => Right(StoreChange.Keep(Shifty("small")))).value shouldBe
      Shifty("small")
    calls.get shouldBe 1
    // replayed: the recorded outcome, the one that was sized
    s.modify[Shifty](ns, "k", key, FencingToken(0))(_ => Right(StoreChange.Keep(Shifty("other")))).value shouldBe
      Shifty("small")
  }

  it should "refuse a change that calls back into the store, so no write is lost" in {
    val s     = store()
    val outer = effectKey()
    val nested = s.modify[Unit](ns, "a", outer, FencingToken(0)) { _ =>
      change[Unit](s, "b")(_ => Right(StoreChange.Put(ujson.Str("b"), ()))).map(_ =>
        StoreChange.Put(ujson.Str("a"), ())
      )
    }
    nested.left.value.message should include("inside another transaction")
    val forgetting = s.modify[Unit](ns, "a", outer, FencingToken(0)) { _ =>
      s.forget(effectKey(), FencingToken(0)).map(_ => StoreChange.Put(ujson.Str("a"), ()))
    }
    forgetting.left.value.message should include("inside another transaction")
    s.get(ns, "a").value shouldBe None
    s.get(ns, "b").value shouldBe None
    // and the store is usable after
    s.modify[Unit](ns, "a", outer, FencingToken(0))(_ => Right(StoreChange.Put(ujson.Str("a"), ()))).value
    text(s, "a") shouldBe Some("a")
  }

  "A long-term store's fence" should "refuse an older claim's token per thread, changing nothing, for modify and forget alike" in {
    val s      = store()
    val thread = freshThread()
    val key    = effectKey(thread)
    def write(token: Long, value: String, on: LongTermStore = s, under: EffectKey = key) =
      on.modify[String](ns, "k", under, FencingToken(token))(_ => Right(StoreChange.Put(ujson.Str(value), value)))
    write(5, "new claimant").value shouldBe "new claimant"
    // the stale run can neither apply, nor read back what is applied, nor forget it
    write(3, "stale").left.value.message should include("stale claim")
    write(3, "stale", under = effectKey(thread)).left.value.message should include("stale claim")
    s.forget(key, FencingToken(3)).left.value.message should include("stale claim")
    // nor through another instance of the store
    write(4, "stale", on = sibling(s), under = effectKey(thread)).left.value.message should include("stale claim")
    sibling(s).forget(key, FencingToken(4)).left.value.message should include("stale claim")
    text(s, "k") shouldBe Some("new claimant")
    appliedKeys(s) shouldBe 1
    // the newest token again, and a newer one, go ahead
    write(5, "ignored").value shouldBe "new claimant"
    s.forget(key, FencingToken(5)).value
    write(6, "newer", under = effectKey(thread)).value shouldBe "newer"
    // another thread's keys have a scope of their own
    write(1, "another thread", under = effectKey()).value shouldBe "another thread"
    text(s, "k") shouldBe Some("another thread")
  }

  it should "keep the newest token it has seen, even when the change it carried was refused or forgot nothing" in {
    val s      = store()
    val thread = freshThread()
    s.modify[Unit](ns, "k", effectKey(thread), FencingToken(7))(_ => Left(BackendRefused("put", "k", "no")))
      .left
      .value shouldBe a[BackendRefused]
    s.modify[Unit](ns, "k", effectKey(thread), FencingToken(6))(_ => Right(StoreChange.Put(ujson.Str("stale"), ())))
      .left
      .value
      .message should include("stale claim")
    s.forget(effectKey(thread), FencingToken(9)).value
    sibling(s)
      .modify[Unit](ns, "k", effectKey(thread), FencingToken(8))(_ => Right(StoreChange.Put(ujson.Str("stale"), ())))
      .left
      .value
      .message should include("stale claim")
    s.get(ns, "k").value shouldBe None
    appliedKeys(s) shouldBe 0
  }

  "Concurrent writers of a long-term store" should "each be counted: no increment is lost" in {
    val s      = store()
    val stores = Vector(s, sibling(s))
    val each   = 25
    val results = together(8) { worker =>
      (1 to each).map { _ =>
        change[Int](stores(worker % 2), "counter") { view =>
          val next = view.current.flatMap(_.value.numOpt).fold(1)(_.toInt + 1)
          Right(StoreChange.Put(ujson.Num(next), next))
        }
      }
    }
    val counts = results.flatten.map(_.value)
    // every increment saw the one before it: the outcomes are 1 to n, each once
    counts.sorted shouldBe (1 to 8 * each).toVector
    s.get(ns, "counter").value.value.value.num shouldBe 8 * each
    appliedKeys(s) shouldBe 8 * each
  }

  it should "never pass a namespace's item limit, whichever of them win" in {
    val s      = store(StoreLimits(maxItems = 5))
    val stores = Vector(s, sibling(s))
    val results = together(8) { worker =>
      (1 to 4).map(i => worker -> put(stores(worker % 2), s"item-$worker-$i", ujson.Str("x")))
    }.flatten
    results.count(_._2.isRight) shouldBe 5
    results.collect { case (_, Left(refused)) => refused }.foreach { refused =>
      refused shouldBe a[BackendRefused]
      refused.message should include("6 items")
    }
    s.list(ns, "", 100).value.items should have size 5
  }

  /** `body` on `workers` threads at once, each given its index; every thread's result, or the case fails. */
  private def together[A](workers: Int)(body: Int => A): Vector[A] =
    val pool  = Executors.newFixedThreadPool(workers)
    val start = new CountDownLatch(1)
    Using.resource(new AutoCloseable { def close(): Unit = pool.shutdownNow(): Unit }) { _ =>
      val futures = (0 until workers).map { worker =>
        val task: java.util.concurrent.Callable[A] = () =>
          start.await()
          body(worker)
        pool.submit(task)
      }
      start.countDown()
      futures.map(_.get(contractWait.toMillis, TimeUnit.MILLISECONDS)).toVector
    }

@Experimental
object LongTermStoreContract:

  private def freshThread(): ThreadId = ThreadId(s"store-contract-${UUID.randomUUID()}")

  /** A key no other call of the contract has, on `thread`: a thread of its own unless one is given. */
  private def effectKey(thread: ThreadId = freshThread()): EffectKey =
    RunContext.outsideRuntime(thread).effectKey("contract", UUID.randomUUID().toString)

  /** The context of one guarded call, outside any runtime, on a thread of its own. */
  private def call(tenant: Option[String] = None): FileContext =
    FileContext.withoutState(
      RunContext.outsideRuntime(freshThread(), RunConfig().withTenantId(tenant.map(TenantId(_))))
    )

  /**
   * A [[ReplayContract.Subject]] over a store's transactional `modify`: slot `n` is the item `slot-<n>` of the
   * namespace `counters`, and the effect increments the number it holds inside the store's transaction, so a second
   * application shows and a replay the store answers from its applied key does not. `applied` counts the store's
   * applied keys, as [[LongTermStoreContract.appliedKeys]] does.
   */
  final class CounterSubject(val name: String, store: LongTermStore, applied: () => Int) extends ReplayContract.Subject:
    private val ns             = Namespace(None, Vector("counters"))
    def support: ReplaySupport = ReplaySupport.Transactional

    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      val effect = FileContext.withoutState(context).effectKey("store.increment", s"slot-$slot")
      ReplayGuard
        .transactional[Int](context, effect, store)(token =>
          store.modify[Int](ns, s"slot-$slot", effect, token) { view =>
            val next = view.current.flatMap(_.value.numOpt).fold(1)(_.toInt + 1)
            Right(StoreChange.Put(ujson.Num(next), next))
          }
        )
        .map(ujson.Num(_))

    def observe(slot: Int): Result[String] =
      store
        .get(ns, s"slot-$slot")
        .map(_.fold("absent")(i => i.value.numOpt.fold(i.value.toString)(n => n.toInt.toString)))
    def initial(slot: Int): String = "absent"
    def once(slot: Int): String    = "1"

    /** Another writer's change, straight to the store, leaving no applied key. */
    def interfere(slot: Int): Unit =
      val effect = effectKey(ThreadId("another-writer"))
      store.modify[Unit](ns, s"slot-$slot", effect, FencingToken(0))(_ =>
        Right(StoreChange.Put(ujson.Str("other"), ()))
      ): Unit
      store.forget(effect, FencingToken(0)): Unit
    override def appliedKeys: Int = applied()
