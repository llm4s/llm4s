package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ AppliedKeys, EffectKey, FencingToken, ReplayGuard, ReplaySupport, TenantId }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ProcessingError, ValidationError }
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
  def render: String =
    ujson.write(ujson.Arr.from(tenant.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.value)) +: path.map(ujson.Str(_))))

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

@Experimental
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
 * What a [[LongTermStore.modify]] change sees, inside its transaction: the item being modified, and the
 * rest of its namespace, so a check that spans keys (a file's ancestors and descendants) is atomic with
 * the write. Values are copies: changing one changes nothing stored.
 */
@Experimental
trait StoreView:

  /** The item at the key being modified. */
  def current: Option[StoreItem]

  /** Another item of the namespace. */
  def get(key: String): Result[Option[StoreItem]]

  /** The entries of the namespace whose key starts with `prefix`, in key order, at most `limit`. */
  def withPrefix(prefix: String, limit: Int): Result[Vector[StoreEntry]]

/**
 * Items kept across threads and runs, by `(Namespace, key)`: `/memories/` files ([[StoreFileBackend]])
 * and semantic memory (F7). Its target is `Transactional` (C4).
 *
 * The one mutation primitive is [[modify]], a read-modify-write in the store's own transaction,
 * serialisable within its namespace: the outcome already recorded for the effect's key, or the change
 * decided against a [[StoreView]] and applied, bounds checked and `(key, outcome)` recorded with it. An
 * edit therefore never loses a concurrent write, a check that spans keys cannot be raced, and a replay
 * whose first attempt landed returns the first outcome before it re-reads anything. [[put]] and
 * [[delete]] wrap it in `ReplayGuard.transactional`; a backend over the store uses `modify` the same
 * way with its own keys.
 *
 * `ujson.Value` is mutable, so a store copies every value it is given and every value it hands out.
 * An implementation keeps its applied keys only until C4's guard forgets them, and never by age.
 *
 * [[InMemoryLongTermStore]] is one process's; `PostgresCheckpointer.longTermStore`
 * (`llm4s-agent-checkpoint-postgres`) is shared by every machine of a service. Both pass
 * `LongTermStoreContract` (`llm4s-agent-testkit`), the suite a store of your own is written against.
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
   * In one transaction, serialisable within `ns`, fenced by `token` (C4's [[org.llm4s.agent.graph.AppliedKeys AppliedKeys]]): a `token`
   * older than the newest seen for `effect.scope` is refused with a `Left` and changes nothing;
   * otherwise it becomes the newest, and then: if `effect` was applied, its recorded outcome and no
   * write; otherwise `change`, decided against the namespace as it is now, which may refuse (`Left`:
   * nothing written or recorded), then the namespace's and item's bounds, then the write and
   * `(effect, outcome)` recorded together. An over-limit change is a [[BackendRefused]]. The newest
   * token per scope is kept as long as the store keeps that thread's data: durably, in a real store.
   *
   * The outcome returned is the recorded one, decoded, so a first run and its replay return the same
   * value. `A`'s codec must be a pure function of its value: C4's guard encodes the outcome again for its
   * journal, and a codec that answered differently would record two things.
   */
  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey, token: FencingToken)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A]

  /** Writes `value` at `key`, guarded under `store.put`: `true` when the key was new. */
  final def put(ns: Namespace, key: String, value: ujson.Value, ctx: FileContext): Result[Boolean] =
    LongTermStore.checked("put", ns, key).flatMap { _ =>
      val effect = ctx.effectKey("store.put", ns.render, key)
      ReplayGuard.transactional[Boolean](ctx.run, effect, this)(token =>
        modify[Boolean](ns, key, effect, token)(view => Right(StoreChange.Put(value, view.current.isEmpty)))
      )
    }

  /** Deletes `key`, guarded under `store.delete`: `true` when it existed. */
  final def delete(ns: Namespace, key: String, ctx: FileContext): Result[Boolean] =
    LongTermStore.checked("delete", ns, key).flatMap { _ =>
      val effect = ctx.effectKey("store.delete", ns.render, key)
      ReplayGuard.transactional[Boolean](ctx.run, effect, this)(token =>
        modify[Boolean](ns, key, effect, token) { view =>
          Right(if view.current.isDefined then StoreChange.Remove(true) else StoreChange.Keep(false))
        }
      )
    }

@Experimental
object LongTermStore:

  /** The deepest a stored value may nest arrays and objects. */
  val MaxDepth: Int = 64

  /** The longest key, key prefix or search query, in UTF-8 bytes (a `VPath`'s bound). */
  val MaxKeyBytes: Int = 1024

  /** The most characters a namespace's tenant and path segments may have together. */
  val MaxNamespaceChars: Int = 1024

  /** The most path segments a namespace may have. */
  val MaxNamespaceSegments: Int = 64

  /**
   * `ns` and `key` (a key, a prefix or a query) within their bounds, checked first: before either is
   * copied, encoded, rendered, digested into an effect key or quoted (only a bounded prefix is).
   */
  def checked(operation: String, ns: Namespace, key: String): Result[Unit] =
    // the count first, so the sum below and any rendering are bounded too (an empty segment has no
    // characters, but a million of them render a million-element array)
    lazy val nsChars = ns.tenant.fold(0L)(_.value.length.toLong) + ns.path.iterator.map(_.length.toLong).sum
    if ns.path.size > MaxNamespaceSegments then
      Left(
        BackendRefused(
          operation,
          "namespace",
          s"the namespace has ${ns.path.size} segments, more than $MaxNamespaceSegments"
        )
      )
    else if nsChars > MaxNamespaceChars then
      Left(
        BackendRefused(operation, "namespace", s"the namespace is $nsChars characters, longer than $MaxNamespaceChars")
      )
    else if key.length.toLong > MaxKeyBytes || Text.utf8Bytes(key) > MaxKeyBytes then
      Left(BackendRefused(operation, Text.quoted(key), s"the key is longer than $MaxKeyBytes bytes"))
    else Right(())

  /**
   * `outcome` encoded with its codec, or why not. A codec recurses once per nesting level, and a
   * `StackOverflowError` is not an `Exception`, so `Try` does not stop it: it is caught here, as core's
   * `RegexSafetyManager` catches a recursing matcher's. By then the codec's frames have unwound, so
   * continuing on this thread is safe; every other fatal error still propagates.
   */
  // scalafix:off DisableSyntax.NoKeywordCatch
  private[llm4s] def encoded[A: ReadWriter](outcome: A, maxBytes: Long): Either[Unencoded, ujson.Value] =
    try
      // the codec runs once, into a buffer that stops past `maxBytes`, so an outcome over the cap is
      // refused before more of it is held (rule 12); the tree is parsed from those very bytes, so what is
      // recorded is what was sized, whatever a stateful codec would emit a second time
      val buffer = new Capped(maxBytes)
      upickle.default.writeToOutputStream(outcome, buffer)
      Right(ujson.read(buffer.toByteArray))
    catch {
      case _: Capped.Over        => Left(Unencoded.Over)
      case _: StackOverflowError => Left(Unencoded.Failed(s"the outcome is nested deeper than $MaxDepth levels"))
      case scala.util.control.NonFatal(e) => Left(Unencoded.Failed(s"the outcome does not encode: $e"))
    }
  // scalafix:on DisableSyntax.NoKeywordCatch

  /** Why an outcome was not encoded: over the cap, or a codec that failed. */
  private[llm4s] enum Unencoded:
    case Over
    case Failed(reason: String)

  /** A buffer that stops the writer, before keeping them, once more than `max` bytes are written. */
  final private class Capped(max: Long) extends java.io.ByteArrayOutputStream:
    private def check(n: Int): Unit = if size().toLong + n > max then throw new Capped.Over
    override def write(b: Int): Unit =
      check(1)
      super.write(b)
    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      check(len)
      super.write(b, off, len)

  private object Capped:
    final class Over extends RuntimeException("over the cap", null, false, false)

  /** What a checked change writes: `Put`'s value is the store's own copy, sized in UTF-8 bytes with its key. */
  private[llm4s] enum Write:
    case Put(value: ujson.Value, bytes: Long)
    case Remove
    case Keep

  /** A change within the item's bounds, and its outcome as it is recorded: encoded once, sized and copied. */
  final private[llm4s] case class Checked(write: Write, recorded: ujson.Value)

  /** One item's UTF-8 bytes, its key plus its encoded value, counted (to just past `limits.maxItemBytes`), not rendered. */
  private[llm4s] def itemBytes(key: String, value: ujson.Value, limits: StoreLimits): Long =
    Text.utf8Bytes(key) + Text.jsonValueBytes(value, limits.maxItemBytes.toLong)

  /**
   * `change` as every store checks it before it writes, in this order: a `Put`'s item within
   * `limits.maxItemBytes`, then its value within [[MaxDepth]], then the outcome encoded within the replay cap and
   * [[MaxDepth]]. The namespace's bounds ([[within]]) come after, in the store's transaction.
   */
  private[llm4s] def check[A: ReadWriter](key: String, limits: StoreLimits, change: StoreChange[A]): Result[Checked] =
    change match
      // sized first: the count is iterative and stops at the cap (a wide value at its least size, before
      // its children), so every later walk of the value - depth, copy - is bounded by `maxItemBytes`
      case StoreChange.Put(value, _) if itemBytes(key, value, limits) > limits.maxItemBytes =>
        Left(BackendRefused("put", Text.quoted(key), s"the item is over the limit of ${limits.maxItemBytes} bytes"))
      // a value is walked recursively by every copy, encoding and search, so its depth is checked next,
      // without recursion: a deep one would overflow the stack past any `Try`
      case StoreChange.Put(value, _) if !Text.depthWithin(value, MaxDepth) =>
        Left(BackendRefused("put", Text.quoted(key), s"the value is nested deeper than $MaxDepth levels"))
      case decided =>
        val (write, outcome) = decided match
          case StoreChange.Put(value, o) => (Write.Put(ujson.copy(value), itemBytes(key, value, limits)), o)
          case StoreChange.Remove(o)     => (Write.Remove, o)
          case StoreChange.Keep(o)       => (Write.Keep, o)
        // the applied key outlives a crash before the guard forgets it, so its outcome is bounded too:
        // encoded (contained, as `A`'s codec recurses), checked in depth without recursion, then copied
        // (it may be the caller's own tree) and sized
        encoded(outcome, ReplayGuard.DefaultMaxOutcomeBytes.toLong) match
          case Left(Unencoded.Over) =>
            Left(
              ValidationError(
                "outcome",
                s"the outcome is over the replay cap of ${ReplayGuard.DefaultMaxOutcomeBytes} bytes"
              )
            )
          case Left(Unencoded.Failed(reason)) => Left(BackendRefused("put", Text.quoted(key), reason))
          case Right(json) if !Text.depthWithin(json, MaxDepth) =>
            Left(BackendRefused("put", Text.quoted(key), s"the outcome is nested deeper than $MaxDepth levels"))
          case Right(json) => Right(Checked(write, ujson.copy(json)))

  /**
   * The bounds of a namespace as a change would leave it: `item` is the changed item's bytes (`None` once
   * removed), `items` and `total` the namespace's item count and bytes after the change.
   */
  private[llm4s] def within(
    key: String,
    limits: StoreLimits,
    item: Option[Long],
    items: Long,
    total: Long
  ): Result[Unit] =
    def refuse(reason: String) = Left(BackendRefused("put", Text.quoted(key), reason))
    if item.exists(_ > limits.maxItemBytes) then
      refuse(s"the item is ${item.get} bytes, over the limit of ${limits.maxItemBytes}")
    else if items > limits.maxItems then
      refuse(s"the namespace would hold $items items, over its limit of ${limits.maxItems}")
    else if total > limits.maxBytes then
      refuse(s"the namespace would hold $total bytes, over its limit of ${limits.maxBytes}")
    else Right(())

  /** A recorded outcome decoded, from a copy: `A` may be `ujson.Value`, whose reader hands back the tree it is given. */
  private[llm4s] def decode[A: ReadWriter](recorded: ujson.Value): Result[A] =
    Try(upickle.default.read[A](ujson.copy(recorded))).toResult

  /** Why `key` is not one a store keeps, if it is not: checked inside the transaction, after the fence. */
  private[llm4s] def invalidKey(key: String): Option[BackendRefused] =
    Option.when(!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(key))(
      BackendRefused("put", Text.quoted(key), "the key is not valid Unicode (an unpaired surrogate)")
    )

  /** The error of a token older than the newest a store has seen for its scope. */
  private[llm4s] def stale(token: FencingToken, seen: FencingToken): ProcessingError =
    ProcessingError("long-term-store", s"stale claim: token ${token.value} is older than ${seen.value}")

  /** The error of a `modify` or `forget` called from inside a `modify` change of the same store. */
  private[llm4s] def reentered(operation: String): ProcessingError =
    ProcessingError(
      "long-term-store",
      s"$operation was called from inside another transaction of this store (a modify change); a change " +
        "must not call back into the store"
    )

  /**
   * How the shipped stores match a `search`: `query`, case-insensitively, as a substring of the key or of any
   * string in the value. Locale-independent, so a Turkish JVM matches `I` to `i` as every other does.
   */
  private[llm4s] def matcher(query: String): (String, ujson.Value) => Boolean =
    val needle = query.toLowerCase(java.util.Locale.ROOT)
    def strings(v: ujson.Value): Iterator[String] = v match
      case ujson.Str(s) => Iterator(s)
      case ujson.Arr(a) => a.iterator.flatMap(strings)
      case ujson.Obj(o) => o.valuesIterator.flatMap(strings)
      case _            => Iterator.empty
    (key, value) => (Iterator(key) ++ strings(value)).exists(_.toLowerCase(java.util.Locale.ROOT).contains(needle))

/**
 * The in-memory [[LongTermStore]], for tests and single-process applications: one lock for the store.
 * `search` is a case-insensitive substring match over keys and string values.
 */
@Experimental
final class InMemoryLongTermStore private (val limits: StoreLimits, clock: Clock) extends LongTermStore:
  private var namespaces = Map.empty[Namespace, Map[String, StoreItem]]
  private var applied    = Map.empty[String, ujson.Value]
  private var newest     = Map.empty[String, FencingToken]

  /** Refuses `token` if older than the newest seen for `key.scope`; otherwise makes it the newest. Locked. */
  private def fenced[A](key: EffectKey, token: FencingToken)(body: => Result[A]): Result[A] =
    newest.get(key.scope) match
      case Some(seen) if token.value < seen.value =>
        Left(LongTermStore.stale(token, seen))
      case _ =>
        newest = newest.updated(key.scope, token)
        body

  /** How many applied keys the store holds: C4's guard forgets each once its outcome is recorded. */
  def appliedKeys: Int = synchronized(applied.size)

  def forget(key: EffectKey, token: FencingToken): Result[Unit] =
    transaction("forget")(fenced(key, token) { applied -= key.value; Right(()) })

  // whether this store's lock is held by a transaction now: `synchronized` is reentrant, so a `change`
  // that called back into the store would run a second transaction inside the first, and the first would
  // then write back a namespace it read before the second; such a call is refused instead
  private var transacting = false

  /** `body` as the store's one transaction, under its lock; a transaction begun inside another is refused. */
  private def transaction[A](operation: String)(body: => Result[A]): Result[A] = synchronized {
    if transacting then Left(LongTermStore.reentered(operation))
    else
      transacting = true
      scala.util.Using.resource(new AutoCloseable { def close(): Unit = transacting = false })(_ => body)
  }

  private def items(ns: Namespace): Map[String, StoreItem] = namespaces.getOrElse(ns, Map.empty)

  /** A detached copy: nothing a caller does to it reaches the store. */
  private def detached(item: StoreItem): StoreItem = StoreItem(ujson.copy(item.value), item.updated)

  def get(ns: Namespace, key: String): Result[Option[StoreItem]] =
    LongTermStore.checked("get", ns, key).flatMap(_ => synchronized(Right(items(ns).get(key).map(detached))))

  def list(ns: Namespace, prefix: String, limit: Int): Result[Listing[StoreEntry]] =
    LongTermStore.checked("list", ns, prefix).flatMap(_ => Text.checkLimit("list", prefix, limit)).map { _ =>
      val all  = synchronized(items(ns))
      val hits = all.toVector.filter(_._1.startsWith(prefix)).sortBy(_._1).iterator
      Listing.of(hits.map((k, item) => StoreEntry(k, detached(item))), limit)
    }

  def search(ns: Namespace, query: String, limit: Int): Result[Listing[StoreEntry]] =
    LongTermStore.checked("search", ns, query).flatMap(_ => Text.checkLimit("search", query, limit)).map { _ =>
      val matches = LongTermStore.matcher(query)
      val all     = synchronized(items(ns))
      val hits = all.toVector.sortBy(_._1).iterator.collect {
        case (k, item) if matches(k, item.value) => StoreEntry(k, detached(item))
      }
      Listing.of(hits, limit)
    }

  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey, token: FencingToken)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A] = LongTermStore.checked("put", ns, key).flatMap(_ => modifyChecked(ns, key, effect, token)(change))

  private def modifyChecked[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey, token: FencingToken)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A] = transaction("modify")(fenced(effect, token) {
    LongTermStore.invalidKey(key) match
      case Some(refused) => Left(refused)
      case None =>
        applied.get(effect.value) match
          case Some(recorded) => LongTermStore.decode[A](recorded)
          case None =>
            val current = items(ns)
            val view = new StoreView:
              val current: Option[StoreItem]                    = items(ns).get(key).map(detached)
              def get(other: String): Result[Option[StoreItem]] = Right(items(ns).get(other).map(detached))
              def withPrefix(prefix: String, limit: Int): Result[Vector[StoreEntry]] =
                Right(
                  items(ns).toVector
                    .filter(_._1.startsWith(prefix))
                    .sortBy(_._1)
                    .take(limit)
                    .map((k, i) => StoreEntry(k, detached(i)))
                )
            change(view).flatMap(LongTermStore.check(key, limits, _)).flatMap { checked =>
              val next = checked.write match
                case LongTermStore.Write.Put(value, _) =>
                  Some(current.updated(key, StoreItem(value, clock.instant())))
                case LongTermStore.Write.Remove => Some(current - key)
                case LongTermStore.Write.Keep   => None
              // the outcome returned is the one recorded, decoded, so this run and a replay of it return
              // the same value, read from the one encoding that was sized
              within(key, next).flatMap(_ => LongTermStore.decode[A](checked.recorded)).map { decoded =>
                next.foreach(n => namespaces = if n.isEmpty then namespaces - ns else namespaces.updated(ns, n))
                applied = applied.updated(effect.value, checked.recorded)
                decoded
              }
            }
  })

  /** The bounds of `next`, the namespace after a change; `None` changes nothing. */
  private def within(key: String, next: Option[Map[String, StoreItem]]): Result[Unit] =
    next.fold[Result[Unit]](Right(())) { items =>
      LongTermStore.within(
        key,
        limits,
        items.get(key).map(i => LongTermStore.itemBytes(key, i.value, limits)),
        items.size.toLong,
        items.iterator.map((k, i) => LongTermStore.itemBytes(k, i.value, limits)).sum
      )
    }

@Experimental
object InMemoryLongTermStore:
  def apply(limits: StoreLimits = StoreLimits.default, clock: Clock = Clock.systemUTC()): InMemoryLongTermStore =
    new InMemoryLongTermStore(limits, clock)
