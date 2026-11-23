package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.*
import org.llm4s.agent.harness.{ BackendRefused, LongTermStore, Namespace, StoreChange }
import org.llm4s.error.ProcessingError
import org.llm4s.it.tags.Docker
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterAll, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Using

/**
 * What `PostgresCheckpointer.longTermStore` adds to the contract every long-term store passes
 * (`PostgresLongTermStoreContractSpec`), against a real server (#2453): the database's clock, storage that outlives
 * the instance, what PostgreSQL text cannot hold, the upgrade of a version-5 schema, the rows an operator reads, the
 * call bound, and what `deleteThread` and a sweep do to a thread's applied keys and fence.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresLongTermStoreSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll {

  private val stores = new PgLongTermStores
  private val ns     = Namespace(None, Vector("notes"))

  private def thread(): ThreadId = ThreadId(s"store-spec-${UUID.randomUUID()}")

  private def keyOf(threadId: ThreadId): EffectKey =
    RunContext.outsideRuntime(threadId).effectKey("spec", UUID.randomUUID().toString)

  private def put(
    store: LongTermStore,
    key: String,
    value: String,
    in: Namespace = ns,
    effect: EffectKey = keyOf(thread()),
    token: Long = 0
  ): Result[Unit] =
    store.modify[Unit](in, key, effect, FencingToken(token))(_ => Right(StoreChange.Put(ujson.Str(value), ())))

  private def count(schema: String, table: String): Int =
    PgCheckpointerDb.scalar(s"""SELECT COUNT(*) FROM "$schema".$table""")(_.getInt(1))

  "PostgresCheckpointer.longTermStore" should "stamp an item with the database's clock, never the host's" in {
    val schema = stores.freshSchema()
    val clocks = new PgCheckpointerDb.Clocks(schema)
    val at     = Instant.parse("2001-02-03T04:05:06.123456Z")
    val store  = stores.checkpointer(schema, clocks.sql(clocks.create(at))).longTermStore()
    put(store, "k", "v").value
    store.get(ns, "k").value.value.updated shouldBe at
    store.list(ns, "", 10).value.items.map(_.item.updated) shouldBe Vector(at)
  }

  it should "keep items, applied keys and fences for an instance opened after the first closed" in {
    val schema = stores.freshSchema()
    val first  = stores.checkpointer(schema)
    val t      = thread()
    val effect = keyOf(t)
    put(first.longTermStore(), "k", "kept", effect = effect, token = 5).value
    first.close()
    first.longTermStore().get(ns, "k").left.value shouldBe a[ProcessingError]

    val second = stores.checkpointer(schema).longTermStore()
    second.get(ns, "k").value.map(_.value.str) shouldBe Some("kept")
    // the applied key answers a replay, and the fence refuses a claim older than the newest the store saw
    second
      .modify[Unit](ns, "k", effect, FencingToken(5))(_ => Right(StoreChange.Put(ujson.Str("again"), ())))
      .value
    second.get(ns, "k").value.map(_.value.str) shouldBe Some("kept")
    put(second, "k", "stale", effect = keyOf(t), token = 4).left.value.message should include("stale claim")
  }

  it should "refuse a key or a tenant PostgreSQL text cannot hold, and read one as absent" in {
    val store = stores.fresh()
    val nul   = put(store, "a\u0000b", "v").left.value
    nul shouldBe a[BackendRefused]
    nul.message should include("NUL")
    store.get(ns, "a\u0000b").value shouldBe None
    store.list(ns, "a\u0000", 10).value.items shouldBe empty
    store.get(ns, "bad\ud800").value shouldBe None
    Vector("ten\u0000ant", "ten\ud800ant").foreach { name =>
      val tenant  = Namespace(Some(TenantId(name)), Vector("notes"))
      val refused = put(store, "k", "v", in = tenant).left.value
      refused shouldBe a[BackendRefused]
      refused.message should include("tenant")
      store.get(tenant, "k").value shouldBe None
      store.list(tenant, "", 10).value.items shouldBe empty
      store.search(tenant, "k", 10).value.items shouldBe empty
    }
    // a path segment, a value and a search query may hold both
    val path = Namespace(None, Vector("no\u0000tes", "sur\ud800rogate"))
    put(store, "k", "va\u0000l\ud800ue", in = path).value
    store.get(path, "k").value.map(_.value.str) shouldBe Some("va\u0000l\ud800ue")
    store.search(path, "a\u0000l", 10).value.items.map(_.key) shouldBe Vector("k")
    store.get(Namespace(None, Vector("no\u0000tes", "sur\ud801rogate")), "k").value shouldBe None
    count(stores.schemaOf(store), "checkpoint_store_items") shouldBe 1
  }

  it should "order keys by their UTF-8 bytes, whatever the database's collation" in {
    val store = stores.fresh()
    val keys  = Vector("B", "a", "Z", "é", "_", "a b", "a-b", "aB", "ab", "😀", "￮")
    keys.foreach(k => put(store, k, k).value)
    val byBytes = keys.sortBy(k => k.getBytes("UTF-8").map(b => f"${b & 0xff}%02x").mkString)
    store.list(ns, "", 100).value.items.map(_.key) shouldBe byBytes
    store.search(ns, "", 100).value.items.map(_.key) shouldBe byBytes
  }

  it should "page a search through a namespace larger than one page" in {
    val store = stores.fresh()
    val n     = PostgresLongTermStore.SearchPage * 2 + 7
    (1 to n).foreach(i => put(store, f"k$i%04d", if (i % 100 == 0) "needle" else "hay").value)
    store.search(ns, "NEEDLE", 100).value.items.map(_.key) shouldBe Vector("k0100", "k0200", "k0300", "k0400", "k0500")
    val cut = store.search(ns, "hay", 300).value
    cut.items should have size 300
    cut.truncated shouldBe true
    store.search(ns, "k", n).value.truncated shouldBe false
  }

  it should "write rows an operator can read and delete by tenant" in {
    val store  = stores.fresh()
    val schema = stores.schemaOf(store)
    val acme   = Namespace(Some(TenantId("acme")), Vector("memories", "café"))
    put(store, "/a.md", "héllo", in = acme).value
    put(store, "/b.md", "other", in = Namespace(Some(TenantId("other")), Vector("memories"))).value
    put(store, "/c.md", "none").value
    val row = PgCheckpointerDb.withConnection { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(
          st.executeQuery(
            s"""SELECT tenant_key, namespace::jsonb ->> 2, value_json::jsonb #>> '{}', item_bytes
               |FROM "$schema".checkpoint_store_items WHERE item_key = '/a.md'""".stripMargin
          )
        ) { rows =>
          rows.next()
          (rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4))
        }
      }
    }
    // the key's 5 bytes, and the value's 2 quotes and 6 bytes of text: the size StoreLimits counts
    row shouldBe ("=acme", "café", "héllo", 13L)
    PgCheckpointerDb.execute(s"""DELETE FROM "$schema".checkpoint_store_items WHERE tenant_key = '=acme'""")
    store.get(acme, "/a.md").value shouldBe None
    store.get(ns, "/c.md").value.map(_.value.str) shouldBe Some("none")
    count(schema, "checkpoint_store_items") shouldBe 2
  }

  it should "fail a modify at its call timeout while another transaction holds its namespace, changing nothing" in {
    PgCheckpointerDb.require()
    val schema = stores.freshSchema()
    val config = PostgresCheckpointerConfig(schema = schema, callTimeout = 1.second)
    val owner  = PgCheckpointerDb.store(config)
    val store  = owner.longTermStore()
    val effect = keyOf(thread())
    val began  = System.nanoTime()
    val held = PgCheckpointerDb.withConnection { c =>
      c.setAutoCommit(false)
      Using.resource(c.prepareStatement(Sql.lockStoreNamespace)) { st =>
        st.setString(1, s"""llm4s.agent.store:"$schema":${PostgresLongTermStore.id(ns)}""")
        st.executeQuery().close()
      }
      val refused = put(store, "k", "v", effect = effect)
      c.rollback()
      refused
    }
    held.left.value shouldBe a[ProcessingError]
    (System.nanoTime() - began).nanos should be < 10.seconds
    store.get(ns, "k").value shouldBe None
    count(schema, "checkpoint_store_applied") shouldBe 0
    // and once the lock is free the same effect applies
    put(store, "k", "v", effect = effect).value
    store.get(ns, "k").value.map(_.value.str) shouldBe Some("v")
    owner.close()
  }

  "A version-5 schema" should "open at the current version with an empty long-term store, its threads untouched" in {
    PgCheckpointerDb.require()
    val schema = stores.freshSchema()
    PgCheckpointerDb.withConnection { c =>
      c.setAutoCommit(false)
      PostgresSchema.migrate(c, schema, target = 5).value
      c.commit()
    }
    val q = s""""$schema""""
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe 5
    PgCheckpointerDb.scalar(s"SELECT to_regclass('$q.checkpoint_store_items') IS NULL")(_.getBoolean(1)) shouldBe true
    PgCheckpointerDb.execute(
      s"INSERT INTO $q.checkpoint_threads (thread_id, next_seq, earliest_seq, first_seq) VALUES ('before', 4, 1, 1)"
    )
    val upgraded = stores.checkpointer(schema)
    PgCheckpointerDb.scalar(s"SELECT version FROM $q.checkpoint_schema")(_.getInt(1)) shouldBe PostgresSchema.Current
    PgCheckpointerDb.scalar(s"SELECT next_seq FROM $q.checkpoint_threads WHERE thread_id = 'before'")(
      _.getLong(1)
    ) shouldBe 4L
    val store = upgraded.longTermStore()
    store.list(ns, "", 10).value.items shouldBe empty
    put(store, "k", "v").value
    store.get(ns, "k").value.map(_.value.str) shouldBe Some("v")
  }

  private def checkpoint(id: String, on: ThreadId) =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      None,
      on.value,
      "run",
      CheckpointStatus.Running,
      Instant.now(),
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )

  "deleteThread" should "remove the thread's applied keys at once, and its fence with its tombstone; never an item" in {
    val schema       = stores.freshSchema()
    val checkpointer = stores.checkpointer(schema)
    val store        = checkpointer.longTermStore()
    // a thread that numbered an event leaves a tombstone; one that numbered none leaves no row
    val numbered = thread()
    val bare     = thread()
    val other    = thread()
    val tokens = Vector(numbered, bare, other).map { t =>
      t -> checkpointer.claim(t, ClaimRequest(RunId("run"), None, 30.seconds)).value.token
    }.toMap
    val event = EventDraft("run", None, None, None, Instant.now(), RunEvent.Custom("e", 1, ujson.Obj()))
    checkpointer
      .commit(numbered, Commit(tokens(numbered), Some(checkpoint("c1", numbered)), events = Vector(event)))
      .value
    Vector(numbered, bare, other).foreach { t =>
      put(store, s"item-${t.value}", "kept", effect = keyOf(t), token = tokens(t).value).value
    }
    count(schema, "checkpoint_store_applied") shouldBe 3
    count(schema, "checkpoint_store_fences") shouldBe 3

    checkpointer.deleteThread(numbered, tokens(numbered), None).value
    count(schema, "checkpoint_store_applied") shouldBe 2
    count(schema, "checkpoint_store_fences") shouldBe 3
    // the tombstone's fence still refuses the deleted thread's older claims
    put(store, "late", "stale", effect = keyOf(numbered), token = tokens(numbered).value - 1).left.value.message should
      include("stale claim")

    checkpointer.deleteThread(bare, tokens(bare), None).value
    count(schema, "checkpoint_store_applied") shouldBe 1
    count(schema, "checkpoint_store_fences") shouldBe 2

    // a sweep that reclaims the tombstone takes its fence, and nothing of a live thread
    val policy = SweepPolicy(tombstoneAge = Some(1.hour))
    checkpointer.sweep(policy, None, SweepGuard.none).value.reclaimed shouldBe 0
    count(schema, "checkpoint_store_fences") shouldBe 2
    PgCheckpointerDb.execute(
      s"""UPDATE "$schema".checkpoint_threads SET deleted_at = deleted_at - INTERVAL '2 hours'"""
    )
    checkpointer.sweep(policy, None, SweepGuard.none).value.reclaimed shouldBe 1
    count(schema, "checkpoint_store_fences") shouldBe 1
    count(schema, "checkpoint_store_applied") shouldBe 1

    // items belong to no thread: every one is still there
    store.list(ns, "item-", 10).value.items should have size 3
  }

  override def afterAll(): Unit = {
    stores.close()
    super.afterAll()
  }
}
