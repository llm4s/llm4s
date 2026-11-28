package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.{ EffectKey, FencingToken, KeyDigest, TenantId }
import org.llm4s.agent.harness.*
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.sql.{ Connection, ResultSet }
import java.time.Instant
import scala.annotation.tailrec
import scala.util.Using

/**
 * The [[org.llm4s.agent.harness.LongTermStore LongTermStore]] of a [[PostgresCheckpointer]]
 * (`PostgresCheckpointer.longTermStore`, #2453): items, applied keys and fences in the store's schema (version 6),
 * each call one transaction on a pooled connection, bounded as the store's calls are. It passes
 * `llm4s-agent-testkit`'s `LongTermStoreContract`, as `InMemoryLongTermStore` does, and checks every bound with the
 * code that store checks them with.
 *
 *  - '''`modify`''' runs in one read-committed transaction. It first makes its token the newest of the effect's
 *    scope in `checkpoint_store_fences` - refused, changing nothing, if the scope has a newer one - which locks the
 *    scope's row, so calls of one thread run one at a time. A key already in `checkpoint_store_applied` is answered
 *    with its recorded outcome. Otherwise it takes a transaction-scoped advisory lock on the namespace, and only then
 *    reads it: every `modify` of a namespace, from any instance on any machine, sees the one before it, so it is
 *    serialisable within the namespace. The item's write, or removal, and `(effect, outcome)` commit together. A
 *    change that refuses, or goes over a bound, writes and records nothing, and its token stays the newest.
 *  - '''Reads''' (`get`, `list`, `search`) run in a repeatable-read, read-only transaction and never wait for a writer.
 *  - '''`search`''' matches as the in-memory store does - the query, case-insensitively, as a substring of the key or
 *    of any string in the value - by reading the namespace in key order, a page at a time, and matching in this JVM,
 *    so the match does not depend on the database's collation. A namespace is bounded by [[limits]], and so is the
 *    read. It is a keyword match, not a semantic one: the SPI's `search` takes text, and no embedding.
 *  - '''Time''' is the database's: `StoreItem.updated` is its clock at the write, to the microsecond.
 *  - '''Keys''' are compared and ordered byte by byte, in UTF-8. Text in PostgreSQL holds neither NUL nor an
 *    unpaired surrogate, so a key, or a namespace's tenant, with either is refused by `modify` with a
 *    `BackendRefused`, and read as absent; values and namespace paths are stored as ASCII JSON and may hold both.
 *  - '''Bounds.''' [[limits]] are this instance's, checked in the transaction against what the namespace holds
 *    now: instances with different limits over one schema each enforce their own.
 *
 * A `change` runs while its transaction holds a connection, the scope's row and the namespace's lock: it must not
 * block, and it must return within the store's call timeout. A `modify` or `forget` it makes on this store is
 * refused, as the in-memory store refuses it.
 */
final private[postgres] class PostgresLongTermStore(store: PostgresCheckpointer, val limits: StoreLimits)
    extends LongTermStore:

  import PostgresCheckpointer.*
  import PostgresLongTermStore.*

  private val t                                   = store.t
  private given PostgresCheckpointer.ErrorContext = store.errorContext

  // ---- an operator's deletes (#3269) ----

  override def deleteNamespace(ns: Namespace): Result[Int] =
    if unstorable(ns) then Right(0)
    else store.writing("store.deleteNamespace")(c => Right(updated(c, Sql.deleteStoreNamespace(t), id(ns))))

  override def deleteTenant(tenant: Option[TenantId]): Result[Int] =
    if tenant.exists(t => unstorable(t.value)) then Right(0)
    else store.writing("store.deleteTenant")(c => Right(updated(c, Sql.deleteStoreTenant(t), tenantKey(tenant))))

  // ---- reads ----

  def get(ns: Namespace, key: String): Result[Option[StoreItem]] =
    LongTermStore.checked("get", ns, key).flatMap { _ =>
      if unstorable(ns) || unstorable(key) then Right(None)
      else store.reading("store.get")(c => item(c, "store.get", id(ns), key))
    }

  def list(ns: Namespace, prefix: String, limit: Int): Result[Listing[StoreEntry]] =
    LongTermStore.checked("list", ns, prefix).flatMap(_ => Text.checkLimit("list", prefix, limit)).flatMap { _ =>
      if unstorable(ns) || unstorable(prefix) then Right(Listing(Vector.empty, truncated = false))
      else
        store.reading("store.list") { c =>
          // one more than the limit tells whether there are more
          entries(c, "store.list", Sql.storeItemsWithPrefix(t), id(ns), prefix, limit.toLong + 1)
            .map(found => Listing.of(found.iterator, limit))
        }
    }

  def search(ns: Namespace, query: String, limit: Int): Result[Listing[StoreEntry]] =
    LongTermStore.checked("search", ns, query).flatMap(_ => Text.checkLimit("search", query, limit)).flatMap { _ =>
      if unstorable(ns) then Right(Listing(Vector.empty, truncated = false))
      else
        store.reading("store.search") { c =>
          val nsId    = id(ns)
          val matches = LongTermStore.matcher(query)
          // the namespace in key order, a page at a time, until one hit more than the limit or its end
          @tailrec def scan(after: Option[String], hits: Vector[StoreEntry]): Result[Vector[StoreEntry]] =
            val page = after match
              case None      => entries(c, "store.search", Sql.storeItemsFirst(t), nsId, SearchPage)
              case Some(key) => entries(c, "store.search", Sql.storeItemsAfter(t), nsId, key, SearchPage)
            page match
              case Left(error) => Left(error)
              case Right(rows) =>
                val found = hits ++ rows.filter(entry => matches(entry.key, entry.item.value))
                if rows.size < SearchPage || found.size > limit then Right(found)
                else scan(Some(rows.last.key), found)
          scan(None, Vector.empty).map(found => Listing.of(found.iterator, limit))
        }
    }

  // ---- writes ----

  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey, token: FencingToken)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A] =
    LongTermStore.checked("put", ns, key).flatMap { _ =>
      if store.inStoreChange then Left(LongTermStore.reentered("modify"))
      else
        // a stale token rolls the transaction back; anything refused after the fence commits it, with nothing
        // written but the fence, so the token stays the newest the scope has seen
        store
          .writing("store.modify")(c => fenced(c, effect, token).map(_ => decided(c, ns, key, effect)(change)))
          .flatten
    }

  def forget(key: EffectKey, token: FencingToken): Result[Unit] =
    if store.inStoreChange then Left(LongTermStore.reentered("forget"))
    else
      store.writing("store.forget") { c =>
        fenced(c, key, token).map(_ => update(c, Sql.forgetStoreApplied(t), key.value))
      }

  /** Refuses `token` if older than the newest seen for `effect.scope`; otherwise makes it the newest, locking the scope. */
  private def fenced(c: Connection, effect: EffectKey, token: FencingToken): Result[Unit] =
    if query(c, Sql.storeFence(t), effect.scope, token.value)(_.getLong(1)).nonEmpty then Right(())
    else
      val seen = query(c, Sql.storeFenceOf(t), effect.scope)(_.getLong(1)).headOption.getOrElse(token.value)
      Left(LongTermStore.stale(token, FencingToken(seen)))

  /** The recorded outcome of an applied `effect`, or `change` decided against the locked namespace and applied. */
  private def decided[A: ReadWriter](c: Connection, ns: Namespace, key: String, effect: EffectKey)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A] =
    refusal(ns, key) match
      case Some(refused) => Left(refused)
      case None =>
        query(c, Sql.storeApplied(t), effect.value)(_.getString(1)).headOption match
          case Some(json) =>
            decoding("store.modify", "recorded outcome")(ujson.read(json)).flatMap(LongTermStore.decode[A])
          case None =>
            val nsId = id(ns)
            query(c, Sql.lockStoreNamespace, s"llm4s.agent.store:${t.quotedSchema}:$nsId")(_ => ()): Unit
            for
              current <- item(c, "store.modify", nsId, key)
              made    <- deciding(new View(c, nsId, current))(change)
              checked <- LongTermStore.check(key, limits, made)
              _       <- bounds(c, nsId, key, checked.write)
              // the outcome returned is the one recorded, decoded, so this call and a replay of it return the same
              decoded <- LongTermStore.decode[A](checked.recorded)
            yield
              checked.write match
                case LongTermStore.Write.Put(value, bytes) =>
                  update(
                    c,
                    Sql.putStoreItem(t),
                    nsId,
                    key,
                    tenantKey(ns.tenant),
                    rendered(ns),
                    json(value),
                    bytes,
                    micros(store.clockNow(c))
                  )
                case LongTermStore.Write.Remove => update(c, Sql.removeStoreItem(t), nsId, key)
                case LongTermStore.Write.Keep   => ()
              update(c, Sql.recordStoreApplied(t), effect.value, effect.scope, json(checked.recorded))
              decoded

  /** `change` run against `view`, which ends with it, with this thread marked as inside a change meanwhile. */
  private def deciding[A](view: View)(change: StoreView => Result[StoreChange[A]]): Result[StoreChange[A]] =
    store.enterStoreChange()
    Using.resource(new AutoCloseable {
      def close(): Unit =
        view.end()
        store.leaveStoreChange()
    })(_ => change(view))

  /** The namespace's bounds as a `Put` would leave it, counted by the database under the namespace's lock. */
  private def bounds(c: Connection, nsId: String, key: String, write: LongTermStore.Write): Result[Unit] =
    write match
      case LongTermStore.Write.Put(_, bytes) =>
        val (items, total, existing, replaced) =
          query(c, Sql.storeTotals(t), key, key, nsId)(r =>
            (r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4))
          ).head
        LongTermStore.within(key, limits, Some(bytes), items - existing + 1, total - replaced + bytes)
      case _ => Right(())

  // ---- rows ----

  private def item(c: Connection, operation: String, nsId: String, key: String): Result[Option[StoreItem]] =
    entries(c, operation, Sql.storeItem(t), nsId, key).map(_.headOption.map(_.item))

  /** The rows `sql` reads, decoded; a row that does not decode is a `ProcessingError` naming the operation. */
  private def entries(c: Connection, operation: String, sql: String, params: Any*): Result[Vector[StoreEntry]] =
    query(c, sql, params*)(row).foldLeft[Result[Vector[StoreEntry]]](Right(Vector.empty)) { (done, raw) =>
      done.flatMap { entries =>
        decoding(operation, "long-term store item")(ujson.read(raw.json)).map { value =>
          entries :+ StoreEntry(raw.key, StoreItem(value, raw.updated))
        }
      }
    }

  /** What a change sees: its namespace, read in the change's own transaction, under the namespace's lock. */
  final private class View(c: Connection, nsId: String, val current: Option[StoreItem]) extends StoreView:
    @volatile private var open = true

    /** The change returned: the transaction this view read in is over. */
    def end(): Unit = open = false

    private def reading[A](body: => Result[A]): Result[A] =
      if open then body
      else Left(ProcessingError("long-term-store", "a StoreView was used after the modify it belongs to returned"))

    def get(key: String): Result[Option[StoreItem]] =
      reading(if unstorable(key) then Right(None) else item(c, "store.modify", nsId, key))

    def withPrefix(prefix: String, limit: Int): Result[Vector[StoreEntry]] =
      reading {
        if unstorable(prefix) || limit <= 0 then Right(Vector.empty)
        else entries(c, "store.modify", Sql.storeItemsWithPrefix(t), nsId, prefix, limit.toLong)
      }

private[postgres] object PostgresLongTermStore:

  /** How many items `search` reads at a time. */
  val SearchPage: Int = 256

  final private case class Raw(key: String, json: String, updated: Instant)

  private def row(rows: ResultSet): Raw =
    Raw(rows.getString(1), rows.getString(2), PostgresCheckpointer.instant(rows, 3))

  /** Whether PostgreSQL `text` cannot hold `s`: it has a NUL, or an unpaired surrogate (not valid Unicode). */
  def unstorable(s: String): Boolean =
    s.indexOf('\u0000') >= 0 || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(s)

  /** Whether `ns` cannot be kept: its tenant is stored as text. Its path is stored as ASCII JSON, so never is. */
  def unstorable(ns: Namespace): Boolean = ns.tenant.exists(tenant => unstorable(tenant.value))

  /** Why a `modify` of `key` in `ns` is refused whatever its change, if it is. */
  def refusal(ns: Namespace, key: String): Option[BackendRefused] =
    LongTermStore
      .invalidKey(key)
      .orElse(
        Option.when(unstorable(key))(
          BackendRefused("put", Text.quoted(key), "the key contains NUL, which a PostgreSQL store cannot keep")
        )
      )
      .orElse(
        Option.when(unstorable(ns))(
          BackendRefused(
            "put",
            "namespace",
            "the namespace's tenant contains NUL or an unpaired surrogate, which a PostgreSQL store cannot keep"
          )
        )
      )

  /**
   * The id of `ns` in the store's tables: a digest of its tenant and path, each part length-prefixed, so no two
   * namespaces share one - a namespace itself may be longer than an index row holds.
   */
  def id(ns: Namespace): String =
    KeyDigest.of(Seq("llm4s-store-namespace-v1", ns.tenant.fold("-")(tenant => "=" + tenant.value)) ++ ns.path)

  /** `ns` as its row shows it: `Namespace.render`'s JSON array, in ASCII. */
  def rendered(ns: Namespace): String =
    json(ujson.Arr.from(ns.tenant.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.value)) +: ns.path.map(ujson.Str(_))))

  /**
   * `value` as its column holds it: compact JSON with every character past ASCII escaped, so a string's NUL or
   * unpaired surrogate, neither of which PostgreSQL `text` can hold, is read back as it was written.
   */
  def json(value: ujson.Value): String = ujson.write(value, escapeUnicode = true)
