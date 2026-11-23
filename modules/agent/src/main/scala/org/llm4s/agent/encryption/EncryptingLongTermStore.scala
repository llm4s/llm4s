package org.llm4s.agent.encryption

import org.llm4s.agent.graph.{ EffectKey, FencingToken, ReplayGuard }
import org.llm4s.agent.harness.*
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * A [[org.llm4s.agent.harness.LongTermStore LongTermStore]] that encrypts the values `inner` stores, with keys from
 * `keys` (#2449, #2453; spec `docs/superpowers/specs/2026-11-22-checkpoint-encryption-design.md`, "The long-term
 * store"). The long-term store is a separate SPI, so wrapping the checkpointer with [[EncryptingCheckpointer]] does
 * not cover it. It works over any store - it is `LongTermStoreContract` that checks it - so give it, over the same
 * keys, to everything that takes the store, on every instance:
 *
 * {{{
 * val memories = EncryptingLongTermStore(postgres.longTermStore(), keys)
 * StoreFileBackend(memories)   // its files are encrypted with no more wiring
 * }}}
 *
 * '''What is encrypted''': each item's value, and the outcome the store records with a change until C4's guard
 * forgets it. Each is sealed on its own with AES-256-GCM under a subkey of its namespace, bound to its key id, its
 * tenant, its namespace and its item key - an outcome to its effect key too - so one moved to another item, namespace
 * or tenant does not open. The key is the namespace's tenant's ([[EncryptionKeySource.current]]).
 *
 * '''What stays in plain''', because the store looks items up, lists and bounds by it: item keys, namespaces and
 * their tenants, `updated` timestamps, effect keys and fencing tokens, the id of the key that sealed a value, and the
 * size of every value.
 *
 * '''Reads.''' A value that cannot be opened is a `Left` ([[EncryptionError]]) of the whole call - a `list` or a
 * `search` never leaves the item out, and never returns what is stored in its place. An item stored in plain is
 * refused ([[EncryptionError.NotEncrypted]]) unless [[EncryptionSettings.plaintextReads]] is on.
 *
 * '''`search`''' cannot be the store's own, which would match ciphertext: this store reads the namespace through
 * `inner.list`, opens each value in key order and matches it as the shipped stores do - the query,
 * case-insensitively, as a substring of the key or of any string in the value - stopping one hit past `limit`. Its
 * results are those of an unencrypted `InMemoryLongTermStore` or Postgres store over the same items; the cost is the
 * whole namespace read into memory, which the store's limits bound, and a decryption per item reached. A store with
 * another `search` of its own loses it under this wrapper.
 *
 * '''`modify`''' runs in `inner`'s transaction, fenced and made idempotent by `inner` exactly as without
 * encryption: an applied key is answered with its recorded outcome, opened, and the change is not run. Otherwise the
 * change sees the plaintext of its namespace, and what it decides is checked against [[limits]], sealed and handed
 * to `inner`. The item being changed is opened before the change is asked, so an item that cannot be opened cannot
 * be overwritten or deleted through this store, only through `inner`.
 *
 * '''Limits.''' [[limits]] are in the caller's terms - bytes of what was written, as `inner` counts them
 * unencrypted - and are checked here, in the transaction, with the store's own messages: they are the largest that
 * always fit `inner`'s once sealed, so `inner` never refuses first. To offer exactly some limits, open `inner` with
 * [[EncryptingLongTermStore.storageLimits]] of them. Counting a namespace's bytes reads its items' sizes, so a write
 * reads its namespace once more than `inner` does.
 */
@Experimental
final class EncryptingLongTermStore private (
  val inner: LongTermStore,
  keys: EncryptionKeySource,
  settings: EncryptionSettings
) extends LongTermStore:
  import EncryptingLongTermStore.*
  import PayloadCipher.traverse

  /**
   * The bounds of what callers write: the largest whose items always fit `inner`'s limits once sealed. An item is
   * counted as its key plus the JSON that is sealed - the bytes an unencrypted store counts, except for a value
   * holding an unpaired surrogate, which is sealed with its characters past ASCII escaped and counted so.
   */
  val limits: StoreLimits = offered(inner.limits)

  private def cipher(ns: Namespace) = PayloadCipher.ofNamespace(ns, keys, settings)

  private def opened(cipher: PayloadCipher, key: String, item: StoreItem): Result[StoreItem] =
    cipher.open(PayloadCipher.item(key), item.value).map(StoreItem(_, item.updated))

  private def opened(cipher: PayloadCipher, entry: StoreEntry): Result[StoreEntry] =
    opened(cipher, entry.key, entry.item).map(StoreEntry(entry.key, _))

  private def opened(cipher: PayloadCipher, key: String, item: Option[StoreItem]): Result[Option[StoreItem]] =
    item.fold[Result[Option[StoreItem]]](Right(None))(opened(cipher, key, _).map(Some(_)))

  /** The item at `key`, its value opened. */
  def get(ns: Namespace, key: String): Result[Option[StoreItem]] =
    inner.get(ns, key).flatMap(opened(cipher(ns), key, _))

  /** The entries `inner` lists, each value opened. */
  def list(ns: Namespace, prefix: String, limit: Int): Result[Listing[StoreEntry]] =
    val open = cipher(ns)
    inner.list(ns, prefix, limit).flatMap { listing =>
      traverse(listing.items)(opened(open, _)).map(Listing(_, listing.truncated))
    }

  /** The entries of `ns` whose key or opened value matches `query`, as the shipped stores match, in key order. */
  def search(ns: Namespace, query: String, limit: Int): Result[Listing[StoreEntry]] =
    for
      _   <- LongTermStore.checked("search", ns, query)
      _   <- Text.checkLimit("search", query, limit)
      all <- inner.list(ns, "", Int.MaxValue)
      hits <- {
        val open    = cipher(ns)
        val matches = LongTermStore.matcher(query)
        // opened in key order, only until one hit more than the limit: that one says there are more
        all.items.foldLeft[Result[Vector[StoreEntry]]](Right(Vector.empty)) {
          case (Right(found), entry) if found.size <= limit =>
            opened(open, entry).map(plain => if matches(plain.key, plain.item.value) then found :+ plain else found)
          case (done, _) => done
        }
      }
    yield Listing(hits.take(limit), hits.size > limit)

  /** `inner`'s transaction, its change deciding over plaintext, and what it decides stored sealed. */
  def modify[A: ReadWriter](ns: Namespace, key: String, effect: EffectKey, token: FencingToken)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[A] =
    val open    = cipher(ns)
    val outcome = PayloadCipher.outcome(key, effect.value)
    inner
      .modify[ujson.Value](ns, key, effect, token) { view =>
        for
          current <- opened(open, key, view.current)
          decided <- change(new Opened(open, view, current))
          // the item's size and depth, and the outcome encoded - once, here - as every store checks them
          checked   <- LongTermStore.check(key, limits, decided)
          sealedBy  <- keys.current(ns.tenant)
          recording <- open.seal(sealedBy, outcome, checked.recorded)
          write <- checked.write match
            case LongTermStore.Write.Put(value, _) =>
              for
                stored <- open.seal(sealedBy, PayloadCipher.item(key), value)
                _      <- within(view, key, stored)
              yield StoreChange.Put(stored, recording)
            case LongTermStore.Write.Remove => Right(StoreChange.Remove(recording))
            case LongTermStore.Write.Keep   => Right(StoreChange.Keep(recording))
        yield write
      }
      .flatMap(open.open(outcome, _))
      .flatMap(LongTermStore.decode[A])

  /** The namespace's bounds as putting `stored` at `key` would leave it, counted over what its items seal. */
  private def within(view: StoreView, key: String, stored: ujson.Value): Result[Unit] =
    view.withPrefix("", Int.MaxValue).flatMap { entries =>
      val others = entries.filter(_.key != key)
      val item   = written(key, stored)
      val total  = others.iterator.map(entry => written(entry.key, entry.item.value)).sum + item
      LongTermStore.within(key, limits, Some(item), others.size + 1L, total)
    }

  /** Passed to the store unchanged: an applied key and a token are stored in plain. */
  def forget(key: EffectKey, token: FencingToken): Result[Unit] = inner.forget(key, token)

  /** What a change sees: `view`, each value opened as it is read. */
  final private class Opened(open: PayloadCipher, view: StoreView, val current: Option[StoreItem]) extends StoreView:
    def get(key: String): Result[Option[StoreItem]] = view.get(key).flatMap(opened(open, key, _))
    def withPrefix(prefix: String, limit: Int): Result[Vector[StoreEntry]] =
      view.withPrefix(prefix, limit).flatMap(traverse(_)(opened(open, _)))

@Experimental
object EncryptingLongTermStore:

  /**
   * `inner`, storing its items' values encrypted with `keys`. Throws `IllegalArgumentException` if `inner`'s limits
   * are too small to hold any sealed item: open it with [[storageLimits]] of the limits wanted.
   */
  def apply(
    inner: LongTermStore,
    keys: EncryptionKeySource,
    settings: EncryptionSettings = EncryptionSettings()
  ): EncryptingLongTermStore = new EncryptingLongTermStore(inner, keys, settings)

  /**
   * The limits to open a store with, for an [[EncryptingLongTermStore]] over it to offer exactly `limits`: room for
   * each item's envelope - its marker, a key id of up to 64 characters, a nonce and a tag - and for its ciphertext in
   * Base64, four bytes for three. An item of `n` bytes needs at most `121 + 4n/3`.
   *
   * Throws `IllegalArgumentException` when no store can hold them: a sealed item of `limits.maxItemBytes` would be
   * over the bound every store has on an item, `ReplayGuard.DefaultMaxOutcomeBytes`.
   */
  def storageLimits(limits: StoreLimits): StoreLimits =
    val item  = PayloadCipher.sealedBytes(limits.maxItemBytes.toLong, PayloadCipher.MaxKeyIdChars)
    val bytes = BigInt(PerItem) * limits.maxItems + (BigInt(4) * limits.maxBytes + 2) / 3
    require(
      item <= ReplayGuard.DefaultMaxOutcomeBytes,
      s"an item of ${limits.maxItemBytes} bytes is $item bytes once sealed, over the ${ReplayGuard.DefaultMaxOutcomeBytes} a store holds"
    )
    require(bytes.isValidLong, s"a namespace of ${limits.maxBytes} bytes is too large to seal")
    StoreLimits(item.toInt, limits.maxItems, bytes.toLong)

  /**
   * The most a sealed item is over four thirds of what it seals, in bytes: the envelope around a key id of 64
   * characters, and the nonce and tag in Base64.
   */
  private val PerItem: Long = PayloadCipher.sealedBytes(0, PayloadCipher.MaxKeyIdChars)

  /** The largest limits whose items, sealed, always fit a store with limits `storage`: [[storageLimits]] undone. */
  private def offered(storage: StoreLimits): StoreLimits =
    val item  = PayloadCipher.sealableBytes(storage.maxItemBytes.toLong, PayloadCipher.MaxKeyIdChars)
    val bytes = (BigInt(storage.maxBytes) - BigInt(PerItem) * storage.maxItems) * 3 / 4
    require(
      item >= 1 && bytes >= 1,
      s"a store limited to items of ${storage.maxItemBytes} bytes, and ${storage.maxItems} items in " +
        s"${storage.maxBytes} bytes, cannot hold an encrypted item: open it with EncryptingLongTermStore.storageLimits"
    )
    StoreLimits(item.toInt, storage.maxItems, bytes.toLong)

  /** One item's bytes as a caller wrote it: its key, and what its stored value seals (itself, if stored in plain). */
  private def written(key: String, stored: ujson.Value): Long =
    Text.utf8Bytes(key) + PayloadCipher.plainBytes(stored).getOrElse(Text.jsonValueBytes(stored, Long.MaxValue))
