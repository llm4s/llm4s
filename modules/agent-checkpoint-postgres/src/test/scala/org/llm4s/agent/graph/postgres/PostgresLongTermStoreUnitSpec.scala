package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.{ ClaimRequest, EffectKey, FencingToken, RunContext, RunId, TenantId, ThreadId }
import org.llm4s.agent.harness.{ BackendRefused, LongTermStore, Namespace, StoreChange, StoreLimits, StoreView }
import org.llm4s.error.ProcessingError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * `PostgresCheckpointer.longTermStore` without a server (#2453): its schema step, how it names and encodes what it
 * stores, and the statements each call makes, in order, over scripted connections. The store against a real server -
 * the `LongTermStoreContract`, concurrency, the database's clock - is `modules/it`'s `@Docker` suites.
 */
class PostgresLongTermStoreUnitSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val t   = PostgresSchema.Tables("llm4s")
  private val now = OffsetDateTime.parse("2026-11-22T10:00:00Z")
  private val ns  = Namespace(Some(TenantId("acme")), Vector("notes"))
  private val id  = PostgresLongTermStore.id(ns)

  private val thread                          = ThreadId("unit-thread")
  private def effect(name: String): EffectKey = RunContext.outsideRuntime(thread).effectKey("unit", name)

  /** A script whose fence accepts every token, with `more` answered first. */
  private def scripted(more: PartialFunction[(String, Vector[Any]), Vector[Vector[Any]]]): ScriptedPostgres =
    ScriptedPostgres(
      now,
      more.orElse {
        case (sql, params) if sql == Sql.storeFence(t) => Vector(Vector(params(1)))
        case (sql, _) if sql == Sql.storeTotals(t)     => Vector(Vector(0L, 0L, 0L, 0L))
      }
    )

  private def statements(script: ScriptedPostgres): Vector[String] = script.made.map(_._1)

  "The long-term store's schema step" should "add its three tables to a version-5 schema, and change no other" in {
    PostgresSchema.Current shouldBe 6
    PostgresSchema.plan("s", 5, PostgresSchema.Current, PostgresSchema.steps).value shouldBe Vector(5)
    val ddl = PostgresSchema.steps(5)(PostgresSchema.Tables("s"))
    ddl.count(_.startsWith("CREATE TABLE")) shouldBe 3
    Vector(""""s".checkpoint_store_items""", """"s".checkpoint_store_applied""", """"s".checkpoint_store_fences""")
      .foreach(table => ddl.exists(_.startsWith(s"CREATE TABLE $table (")) shouldBe true)
    ddl.exists(_.contains("ALTER TABLE")) shouldBe false
    // keys are compared byte by byte, whatever the database's collation
    ddl.head should include("""item_key     text COLLATE "C" NOT NULL""")
  }

  "A namespace's id" should "differ for every namespace, however alike they render" in {
    val alike = Vector(
      Namespace(None, Vector("a", "notes")),
      Namespace(Some(TenantId("a")), Vector("notes")),
      Namespace(Some(TenantId("a")), Vector("not", "es")),
      Namespace(Some(TenantId("a")), Vector("notes", "")),
      Namespace(Some(TenantId("-")), Vector.empty),
      Namespace(None, Vector.empty),
      Namespace(None, Vector("=a", "notes")),
      Namespace(None, Vector("sur\ud800")),
      Namespace(None, Vector("sur\ud801"))
    )
    alike.map(PostgresLongTermStore.id).distinct should have size alike.size
    PostgresLongTermStore.id(ns) shouldBe PostgresLongTermStore.id(Namespace(Some(TenantId("acme")), Vector("notes")))
    PostgresLongTermStore.id(ns) should fullyMatch.regex("[0-9a-f]{64}")
  }

  "What the store writes" should "be ASCII that reads back exactly, whatever a string holds" in {
    val value = ujson.Obj("s" -> "café 😀 nul\u0000 lone\ud800 \"q\" \\ \n", "n" -> 1.5, "i" -> 3)
    val text  = PostgresLongTermStore.json(value)
    text.forall(c => c >= 0x20 && c < 0x7f) shouldBe true
    ujson.read(text) shouldBe value
    val odd = Namespace(Some(TenantId("té")), Vector("p\u0000", "q\ud800"))
    PostgresLongTermStore.rendered(odd).forall(c => c >= 0x20 && c < 0x7f) shouldBe true
    ujson.read(PostgresLongTermStore.rendered(odd)) shouldBe ujson.Arr("té", "p\u0000", "q\ud800")
    ujson.read(PostgresLongTermStore.rendered(Namespace(None, Vector("p")))) shouldBe ujson.Arr(ujson.Null, "p")
  }

  "A key or tenant PostgreSQL text cannot hold" should "be refused by modify, and read as absent without a statement" in {
    PostgresLongTermStore.unstorable("plain é 😀") shouldBe false
    PostgresLongTermStore.unstorable("nul\u0000") shouldBe true
    PostgresLongTermStore.unstorable("lone\ud800") shouldBe true
    PostgresLongTermStore.refusal(ns, "k") shouldBe None
    PostgresLongTermStore.refusal(ns, "bad\ud800").value.reason should include("not valid Unicode")
    PostgresLongTermStore.refusal(ns, "nul\u0000").value.reason should include("NUL")
    val tenant = Namespace(Some(TenantId("t\u0000")), Vector("notes"))
    PostgresLongTermStore.refusal(tenant, "k").value.reason should include("tenant")

    val script = scripted(PartialFunction.empty)
    val store  = script.store().longTermStore()
    store.get(ns, "nul\u0000").value shouldBe None
    store.get(tenant, "k").value shouldBe None
    store.list(ns, "nul\u0000", 10).value.items shouldBe empty
    store.list(tenant, "", 10).value.items shouldBe empty
    store.search(tenant, "k", 10).value.items shouldBe empty
    script.made shouldBe empty
    // refused inside the transaction, after the fence: the token is still the newest the scope has seen
    val refused = store
      .modify[Unit](ns, "nul\u0000", effect("nul"), FencingToken(3))(_ => Right(StoreChange.Keep(())))
      .left
      .value
    refused shouldBe a[BackendRefused]
    statements(script) shouldBe Vector(Sql.storeFence(t))
  }

  "modify" should "fence, look for an applied key, lock the namespace, decide, check the bounds and write, in that order" in {
    val script = scripted(PartialFunction.empty)
    val store  = script.store().longTermStore()
    val key    = effect("put")
    store
      .modify[Boolean](ns, "ké", key, FencingToken(7))(view =>
        Right(StoreChange.Put(ujson.Str("vé"), view.current.isEmpty))
      )
      .value shouldBe true
    statements(script) shouldBe Vector(
      Sql.storeFence(t),
      Sql.storeApplied(t),
      Sql.lockStoreNamespace,
      Sql.storeItem(t),
      Sql.storeTotals(t),
      s"SELECT ${PostgresCheckpointer.ServerClock}",
      Sql.putStoreItem(t),
      Sql.recordStoreApplied(t)
    )
    script.madeWith(Sql.storeFence(t)) shouldBe Vector(Vector(key.scope, 7L))
    script.madeWith(Sql.lockStoreNamespace) shouldBe Vector(Vector(s"""llm4s.agent.store:"llm4s":$id"""))
    // the item's size is its key's 3 bytes and its compact value's 5; the value is stored as ASCII
    script.madeWith(Sql.putStoreItem(t)) shouldBe Vector(
      Vector(id, "ké", "=acme", """["acme","notes"]""", "\"v\\u00e9\"", 8L, now)
    )
    script.madeWith(Sql.recordStoreApplied(t)) shouldBe Vector(Vector(key.value, key.scope, "true"))
  }

  it should "refuse a token older than the scope's newest, changing nothing and running no change" in {
    val script = ScriptedPostgres(now, { case (sql, _) if sql == Sql.storeFenceOf(t) => Vector(Vector(9L)) })
    val store  = script.store().longTermStore()
    val runs   = new AtomicInteger()
    val stale = store
      .modify[Unit](ns, "k", effect("stale"), FencingToken(4)) { _ =>
        runs.incrementAndGet()
        Right(StoreChange.Put(ujson.Str("v"), ()))
      }
      .left
      .value
    stale shouldBe a[ProcessingError]
    stale.message should include("stale claim: token 4 is older than 9")
    store.forget(effect("stale"), FencingToken(4)).left.value.message should include("stale claim")
    runs.get shouldBe 0
    statements(script).distinct shouldBe Vector(Sql.storeFence(t), Sql.storeFenceOf(t))
  }

  it should "answer an applied key with its recorded outcome, before it locks or reads the namespace" in {
    val key = effect("applied")
    val script = scripted {
      case (sql, params) if sql == Sql.storeApplied(t) && params == Vector(key.value) => Vector(Vector("\"first\""))
    }
    val store = script.store().longTermStore()
    val runs  = new AtomicInteger()
    store
      .modify[String](ns, "k", key, FencingToken(0)) { _ =>
        runs.incrementAndGet()
        Right(StoreChange.Put(ujson.Str("v"), "second"))
      }
      .value shouldBe "first"
    runs.get shouldBe 0
    statements(script) shouldBe Vector(Sql.storeFence(t), Sql.storeApplied(t))
  }

  it should "write and record nothing for a change that refuses, goes over a bound or removes nothing" in {
    val script = scripted {
      // the namespace already holds 2 items and 40 bytes, none of them this key
      case (sql, _) if sql == Sql.storeTotals(t) => Vector(Vector(2L, 40L, 0L, 0L))
    }
    val store = script.store().longTermStore(StoreLimits(maxItemBytes = 20, maxItems = 2, maxBytes = 50))
    def modify(change: StoreView => org.llm4s.types.Result[StoreChange[Unit]]) =
      store.modify[Unit](ns, "k", effect(java.util.UUID.randomUUID().toString), FencingToken(0))(change)
    modify(_ => Left(BackendRefused("put", "k", "no"))).left.value shouldBe a[BackendRefused]
    modify(_ => Right(StoreChange.Put(ujson.Str("x" * 30), ()))).left.value.message should include(
      "over the limit of 20"
    )
    modify(_ => Right(StoreChange.Put(ujson.Str("x"), ()))).left.value.message should include("3 items")
    statements(script) should contain noneOf (Sql.putStoreItem(t), Sql.recordStoreApplied(t), Sql.removeStoreItem(t))
    // a removal, and a change that keeps the item, check no bound and read no clock
    modify(_ => Right(StoreChange.Remove(()))).value
    modify(_ => Right(StoreChange.Keep(()))).value
    script.madeWith(Sql.removeStoreItem(t)) shouldBe Vector(Vector(id, "k"))
    script.madeWith(Sql.recordStoreApplied(t)) should have size 2
    script.madeWith(Sql.storeTotals(t)) should have size 1
    statements(script) should not contain s"SELECT ${PostgresCheckpointer.ServerClock}"
  }

  it should "count an overwrite once: the replaced item's bytes leave the namespace's total" in {
    val script = scripted {
      // 2 items and 40 bytes, 18 of them this key's
      case (sql, params) if sql == Sql.storeTotals(t) && params == Vector("k", "k", id) =>
        Vector(Vector(2L, 40L, 1L, 18L))
    }
    val store = script.store().longTermStore(StoreLimits(maxItemBytes = 30, maxItems = 2, maxBytes = 50))
    def put(value: String) =
      store.modify[Unit](ns, "k", effect(value), FencingToken(0))(_ => Right(StoreChange.Put(ujson.Str(value), ())))
    // 40 - 18 + (1 + 2 + 25) is 50: at the limit; one byte more is over it
    put("x" * 25).value
    put("x" * 26).left.value.message should include("51 bytes, over its limit of 50")
  }

  it should "refuse a modify or forget its own change makes, and a view used after it returned" in {
    val script = scripted(PartialFunction.empty)
    val store  = script.store().longTermStore()
    var kept   = Option.empty[StoreView]
    val nested = store.modify[Unit](ns, "a", effect("outer"), FencingToken(0)) { view =>
      kept = Some(view)
      store.forget(effect("inner"), FencingToken(0)).left.value.message should include("inside another transaction")
      store
        .modify[Unit](ns, "b", effect("inner"), FencingToken(0))(_ => Right(StoreChange.Put(ujson.Str("b"), ())))
        .map(_ => StoreChange.Put(ujson.Str("a"), ()))
    }
    nested.left.value.message should include("inside another transaction")
    statements(script) should not contain Sql.putStoreItem(t)
    kept.value.get("a").left.value.message should include("after the modify")
    kept.value.withPrefix("", 10).left.value.message should include("after the modify")
    // and the store is usable after
    store.modify[Unit](ns, "a", effect("outer"), FencingToken(0))(_ => Right(StoreChange.Keep(()))).value
  }

  "The view a change is given" should "read its namespace in the change's transaction" in {
    val script = scripted {
      case (sql, params) if sql == Sql.storeItem(t) && params == Vector(id, "k") => Vector(Vector("k", "1", now))
      case (sql, params) if sql == Sql.storeItem(t) && params == Vector(id, "other") =>
        Vector(Vector("other", "2", now))
      case (sql, params) if sql == Sql.storeItemsWithPrefix(t) && params == Vector(id, "o", 5L) =>
        Vector(Vector("other", "2", now))
    }
    val store = script.store().longTermStore()
    val seen = store.modify[Vector[String]](ns, "k", effect("view"), FencingToken(0)) { view =>
      for
        other   <- view.get("other")
        missing <- view.get("missing")
        nul     <- view.get("nul\u0000")
        under   <- view.withPrefix("o", 5)
        none    <- view.withPrefix("o", 0)
      yield StoreChange.Keep(
        Vector(
          view.current.map(_.value.num).toString,
          other.map(_.value.num).toString,
          missing.toString,
          nul.toString,
          under.map(_.key).mkString(","),
          none.size.toString
        )
      )
    }
    seen.value shouldBe Vector("Some(1.0)", "Some(2.0)", "None", "None", "other", "0")
    seen.value.size shouldBe 6
    script.madeWith(Sql.storeItem(t)) shouldBe Vector(Vector(id, "k"), Vector(id, "other"), Vector(id, "missing"))
  }

  "forget" should "fence, then remove the applied key" in {
    val script = scripted(PartialFunction.empty)
    val key    = effect("forget")
    script.store().longTermStore().forget(key, FencingToken(2)).value
    script.made shouldBe Vector(
      Sql.storeFence(t)         -> Vector(key.scope, 2L),
      Sql.forgetStoreApplied(t) -> Vector(key.value)
    )
  }

  "Reads" should "decode the rows they read, ask for one more than the limit, and fail on a row that does not decode" in {
    val script = scripted {
      case (sql, params) if sql == Sql.storeItem(t) && params == Vector(id, "k") =>
        Vector(Vector("k", """{"a":"café"}""", now))
      case (sql, params) if sql == Sql.storeItem(t) && params == Vector(id, "broken") =>
        Vector(Vector("broken", "{not json", now))
      case (sql, params) if sql == Sql.storeItemsWithPrefix(t) && params == Vector(id, "a/", 3L) =>
        Vector(Vector("a/1", "1", now), Vector("a/2", "2", now), Vector("a/3", "3", now))
    }
    val store = script.store().longTermStore()
    val item  = store.get(ns, "k").value.value
    item.value shouldBe ujson.Obj("a" -> "café")
    item.updated shouldBe now.toInstant
    store.get(ns, "missing").value shouldBe None
    val broken = store.get(ns, "broken").left.value
    broken shouldBe a[ProcessingError]
    broken.message should include("does not decode")
    val listing = store.list(ns, "a/", 2).value
    listing.items.map(_.key) shouldBe Vector("a/1", "a/2")
    listing.truncated shouldBe true
    store.list(ns, "a/", 0).left.value shouldBe a[BackendRefused]
    store.list(ns, "a/", Int.MaxValue).value.items shouldBe empty
    script.madeWith(Sql.storeItemsWithPrefix(t)).last shouldBe Vector(id, "a/", Int.MaxValue.toLong + 1)
  }

  "search" should "read the namespace a page at a time in key order, matching in the JVM, until it has enough" in {
    val page = PostgresLongTermStore.SearchPage
    def rows(from: Int, until: Int) =
      (from until until).toVector.map(i =>
        Vector[Any](f"k$i%05d", if i % 10 == 0 then "\"Needle\"" else "\"hay\"", now)
      )
    val script = scripted {
      case (sql, params) if sql == Sql.storeItemsFirst(t) && params == Vector(id, page) => rows(0, page)
      case (sql, params) if sql == Sql.storeItemsAfter(t) && params == Vector(id, f"k${page - 1}%05d", page) =>
        rows(page, page + 40)
    }
    val store = script.store().longTermStore()
    val all   = store.search(ns, "needle", 1000).value
    all.items.map(_.key) shouldBe (0 until page + 40 by 10).toVector.map(i => f"k$i%05d")
    all.truncated shouldBe false
    statements(script) shouldBe Vector(Sql.storeItemsFirst(t), Sql.storeItemsAfter(t))
    // enough hits in the first page: the second is never read
    val cut = store.search(ns, "NEEDLE", 3).value
    cut.items.map(_.key) shouldBe Vector("k00000", "k00010", "k00020")
    cut.truncated shouldBe true
    statements(script).count(_ == Sql.storeItemsAfter(t)) shouldBe 1
    store.search(ns, "needle", 0).left.value shouldBe a[BackendRefused]
  }

  "A closed store's long-term store" should "refuse every call" in {
    val checkpointer = scripted(PartialFunction.empty).store()
    val store        = checkpointer.longTermStore()
    checkpointer.close()
    store.get(ns, "k").left.value.message should include("the store is closed")
    store.list(ns, "", 1).left.value.message should include("the store is closed")
    store.search(ns, "", 1).left.value.message should include("the store is closed")
    store.forget(effect("closed"), FencingToken(0)).left.value.message should include("the store is closed")
    store
      .modify[Unit](ns, "k", effect("closed"), FencingToken(0))(_ => Right(StoreChange.Keep(())))
      .left
      .value
      .message should include("the store is closed")
  }

  "deleteThread" should "remove the thread's applied keys and, when its row goes, its fence, in its transaction" in {
    val script = ScriptedPostgres(
      now,
      {
        // the thread's row, claimed with token 7
        case (sql, _) if sql.startsWith(Sql.row(t)) =>
          Vector(Vector("c1", 3L, 1L, 1L, "run", 7L, now.plusHours(1), null, null))
      }
    )
    val store = script.store()
    store.deleteThread(thread, FencingToken(7), None).value
    val scope = EffectKey.scopeOf(thread)
    // the scripted row is dropped (every update changes one row), so its fence goes too
    script.madeWith(Sql.deleteStoreApplied(t)) shouldBe Vector(Vector(scope))
    script.madeWith(Sql.deleteStoreFence(t)) shouldBe Vector(Vector(scope))
    statements(script).indexOf(Sql.dropIfEmpty(t)) should be < statements(script).indexOf(Sql.deleteStoreFence(t))
    // a claim, which may drop an empty row of its own, never touches the long-term store
    script.store().claim(ThreadId("other"), ClaimRequest(RunId("r"), None, 30.seconds)): Unit
    statements(script).filter(_.contains("checkpoint_store_")) shouldBe empty
  }

  "The long-term store's limits" should "be the ones it was opened with, the default unless given" in {
    val checkpointer = scripted(PartialFunction.empty).store()
    checkpointer.longTermStore().limits shouldBe StoreLimits.default
    val limits               = StoreLimits(maxItems = 3)
    val store: LongTermStore = checkpointer.longTermStore(limits)
    store.limits shouldBe limits
  }
}
