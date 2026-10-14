package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ AppliedKeys, EffectKey, ReplayGuard, ReplaySupport, TenantId }
import org.llm4s.annotation.Experimental
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.time.{ Clock, Instant }
import scala.util.Try

/**
 * Where a [[LongTermStore]] item lives: always with its tenant (`None` is the single-tenant namespace,
 * its own), then a path. No operation reaches across namespaces (rule 8).
 */
@Experimental
final case class Namespace(tenant: Option[TenantId], path: Vector[String]):

  /** The namespace as a JSON array, tenant (or `null`) first: two namespaces never render alike. */
  def render: String = ujson.write(ujson.Arr.from(tenant.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.value)) +: path.map(ujson.Str(_))))

/** A stored value, and when it was last written. */
@Experimental
final case class StoreItem(value: ujson.Value, updated: Instant)

/** An item with its key, as a listing returns it. */
@Experimental
final case class StoreEntry(key: String, item: StoreItem)

/**
 * A store's bounds (rule 12): one item's UTF-8 bytes (its key plus its encoded value), and one
 * namespace's item count and total bytes. `maxItemBytes` is at most `ReplayGuard.DefaultMaxOutcomeBytes`,
 * so an outcome built from an item always fits a replay record.
 */
@Experimental
final case class StoreLimits private (maxItemBytes: Int, maxItems: Int, maxBytes: Long):
  def withMaxItemBytes(n: Int): StoreLimits = StoreLimits(n, maxItems, maxBytes)
  def withMaxItems(n: Int): StoreLimits     = StoreLimits(maxItemBytes, n, maxBytes)
  def withMaxBytes(n: Long): StoreLimits    = StoreLimits(maxItemBytes, maxItems, n)

object StoreLimits:

  /** 64 KiB per item; 1000 items and 4 MiB per namespace. */
  val default: StoreLimits = apply()

  def apply(maxItemBytes: Int = 64 * 1024, maxItems: Int = 1000, maxBytes: Long = 4L * 1024 * 1024): StoreLimits =
    require(
      maxItemBytes > 0 && maxItemBytes <= ReplayGuard.DefaultMaxOutcomeBytes,
      s"maxItemBytes must be in 1..${ReplayGuard.DefaultMaxOutcomeBytes}, was $maxItemBytes"
    )
    require(maxItems > 0, s"maxItems must be positive, was $maxItems")
    require(maxBytes > 0, s"maxBytes must be positive, was $maxBytes")
    new StoreLimits(maxItemBytes, maxItems, maxBytes)

/** What [[LongTermStore.modify]] does to the current item, and the outcome it records. */
@Experimental
enum StoreChange[+A]:
  case Put(value: ujson.Value, outcome: A)
  case Remove(outcome: A)
  case Keep(outcome: A)

/**
 * Items kept across threads and runs, by `(Namespace, key)`: `/memories/` files ([[StoreFileBackend]])
 * and semantic memory (F7). Its target is `Transactional` (C4).
 *
 * The one mutation primitive is [[modify]], a read-modify-write in the store's own transaction: the
 * outcome already recorded for the effect's key, or the change applied, bounds checked and
 * `(key, outcome)` recorded with it. An edit therefore never loses a concurrent write, and a replay
 * whose first attempt landed returns the first outcome before it re-reads anything. [[put]] and
 * [[delete]] wrap it in `ReplayGuard.transactional`; a backend over the store uses `modify` the same
 * way with its own keys.
 *
 * An implementation keeps its applied keys only until C4's guard forgets them, and never by age.
 */
@Experimental
trait LongTermStore extends AppliedKeys:

  /** C4's class for this target. */
  final val replaySupport: ReplaySupport = ReplaySupport.Transactional

  def limits: StoreLimits

  def get(ns: Namespace, key: String): Result[Option[StoreItem]]

  /** The entries of `ns` whose key starts with `prefix`, in key order, at most `limit`. */
  def list(ns: Namespace, prefix: String, limit: Int): Result[Listing[StoreEntry]]

  /** The entries of `ns` matching `query`, at most `limit`; each store documents how it matches. */
  def search(ns: Namespace, query: String, limit: Int): Result[Listing[StoreEntry]]

  /**
   * In one transaction: if `effect` was applied, its recorded outcome and no write; otherwise `change`
   * of the current item, which may refuse (`Left`: nothing written or recorded), then the namespace's
   * and item's bounds, then the write and `(effect, outcome)` recorded together. An over-limit change is
   * a [[BackendRefused]].
   */
  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey)(
    change: Option[StoreItem] => Result[StoreChange[A]]
  ): Result[A]

  /** Writes `value` at `key`, guarded under `store.put`: `true` when the key was new. */
  final def put(ns: Namespace, key: String, value: ujson.Value, ctx: FileContext): Result[Boolean] =
    val effect = ctx.effectKey("store.put", ns.render, key)
    ReplayGuard.transactional[Boolean](ctx.run, effect, this)(
      modify[Boolean](ns, key, effect)(current => Right(StoreChange.Put(value, current.isEmpty)))
    )

  /** Deletes `key`, guarded under `store.delete`: `true` when it existed. */
  final def delete(ns: Namespace, key: String, ctx: FileContext): Result[Boolean] =
    val effect = ctx.effectKey("store.delete", ns.render, key)
    ReplayGuard.transactional[Boolean](ctx.run, effect, this)(
      modify[Boolean](ns, key, effect) {
        case Some(_) => Right(StoreChange.Remove(true))
        case None    => Right(StoreChange.Keep(false))
      }
    )

/**
 * The in-memory [[LongTermStore]], for tests and single-process applications: one lock for the store.
 * `search` is a case-insensitive substring match over keys and string values.
 */
@Experimental
final class InMemoryLongTermStore private (val limits: StoreLimits, clock: Clock) extends LongTermStore:
  private var namespaces = Map.empty[Namespace, Map[String, StoreItem]]
  private var applied    = Map.empty[String, ujson.Value]

  /** How many applied keys the store holds: C4's guard forgets each once its outcome is recorded. */
  def appliedKeys: Int = synchronized(applied.size)

  def forget(key: EffectKey): Result[Unit] = synchronized { applied -= key.value; Right(()) }

  private def items(ns: Namespace): Map[String, StoreItem] = namespaces.getOrElse(ns, Map.empty)

  def get(ns: Namespace, key: String): Result[Option[StoreItem]] = synchronized(Right(items(ns).get(key)))

  def list(ns: Namespace, prefix: String, limit: Int): Result[Listing[StoreEntry]] =
    Text.checkLimit("list", prefix, limit).map { _ =>
      val all = synchronized(items(ns))
      Listing.of(all.toVector.filter(_._1.startsWith(prefix)).sortBy(_._1).iterator.map(StoreEntry(_, _)), limit)
    }

  def search(ns: Namespace, query: String, limit: Int): Result[Listing[StoreEntry]] =
    Text.checkLimit("search", query, limit).map { _ =>
      val needle = query.toLowerCase
      def strings(v: ujson.Value): Iterator[String] = v match
        case ujson.Str(s) => Iterator(s)
        case ujson.Arr(a) => a.iterator.flatMap(strings)
        case ujson.Obj(o) => o.valuesIterator.flatMap(strings)
        case _            => Iterator.empty
      val all = synchronized(items(ns))
      val hits = all.toVector.sortBy(_._1).iterator.collect {
        case (k, item) if (Iterator(k) ++ strings(item.value)).exists(_.toLowerCase.contains(needle)) =>
          StoreEntry(k, item)
      }
      Listing.of(hits, limit)
    }

  private def itemBytes(key: String, value: ujson.Value): Long = Text.utf8Bytes(key) + Text.utf8Bytes(ujson.write(value))

  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey)(
    change: Option[StoreItem] => Result[StoreChange[A]]
  ): Result[A] = synchronized {
    applied.get(effect.value) match
      case Some(recorded) => Try(upickle.default.read[A](recorded)).toResult
      case None =>
        val current = items(ns)
        change(current.get(key)).flatMap { decided =>
          val (next, outcome) = decided match
            case StoreChange.Put(value, o) => (Some(current.updated(key, StoreItem(value, clock.instant()))), o)
            case StoreChange.Remove(o)     => (Some(current - key), o)
            case StoreChange.Keep(o)       => (None, o)
          within(key, next).map { _ =>
            next.foreach(n => namespaces = if n.isEmpty then namespaces - ns else namespaces.updated(ns, n))
            applied = applied.updated(effect.value, upickle.default.writeJs(outcome))
            outcome
          }
        }
  }

  /** The bounds of `next`, the namespace after a change; `None` changes nothing. */
  private def within(key: String, next: Option[Map[String, StoreItem]]): Result[Unit] =
    next.fold[Result[Unit]](Right(())) { items =>
      def refuse(reason: String) = Left(BackendRefused("put", key, reason))
      val item                   = items.get(key).map(i => itemBytes(key, i.value))
      val total                  = items.iterator.map((k, i) => itemBytes(k, i.value)).sum
      if item.exists(_ > limits.maxItemBytes) then
        refuse(s"the item is ${item.get} bytes, over the limit of ${limits.maxItemBytes}")
      else if items.size > limits.maxItems then
        refuse(s"the namespace would hold ${items.size} items, over its limit of ${limits.maxItems}")
      else if total > limits.maxBytes then
        refuse(s"the namespace would hold $total bytes, over its limit of ${limits.maxBytes}")
      else Right(())
    }

object InMemoryLongTermStore:
  def apply(limits: StoreLimits = StoreLimits.default, clock: Clock = Clock.systemUTC()): InMemoryLongTermStore =
    new InMemoryLongTermStore(limits, clock)
