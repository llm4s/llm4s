package org.llm4s.agent.encryption

import org.llm4s.agent.graph.TenantId
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

/**
 * Where [[EncryptingCheckpointer]] and [[EncryptingRunQueue]] get their keys (#2449): the application's, never the
 * store's. Implement it over a KMS or a secret manager; [[StaticKeySource]] holds keys the application loaded itself.
 *
 * Both methods are called on the store's hot paths - [[current]] once per commit or submission that writes a payload,
 * [[key]] once per key id per read - so an implementation over a remote service caches what it fetched. Rotation is
 * [[current]] answering a key with a new [[KeyId]] while [[key]] still answers the old ones: every ciphertext names
 * the key that wrote it, new writes use the new key, and old rows stay readable until they are pruned.
 *
 * Implementations must be safe to call from several threads, and must never put key material in an error.
 */
@Experimental
trait EncryptionKeySource:

  /**
   * The key new payloads of `tenantId`'s threads are encrypted with; `None` is a thread with no tenant. A `Left`
   * refuses the write: nothing is stored in plain instead.
   */
  def current(tenantId: Option[TenantId]): Result[EncryptionKey]

  /**
   * The key named `keyId`, to read what it wrote - current or retired, of whatever tenant; a `Left`
   * ([[EncryptionError.UnknownKey]]) fails the read.
   */
  def key(keyId: KeyId): Result[EncryptionKey]

/**
 * Keys held in memory (#2449): one for every thread, or one per tenant, with retired keys kept for reading.
 *
 * {{{
 * for key <- EncryptionKey.fromBase64(KeyId("2026-11"), fromSecretStore)
 * yield StaticKeySource(key)                  // one key for every thread
 *   .withTenantKey(TenantId("acme"), acmeKey) // acme's own
 *   .withRetiredKey(lastYearsKey)             // still read, never written with
 * }}}
 *
 * Every key needs an id of its own: adding a second key under an id already held throws `IllegalArgumentException`
 * (the same key again is accepted). `toString` lists key ids only.
 */
@Experimental
final class StaticKeySource private (
  default: Option[EncryptionKey],
  tenants: Map[String, EncryptionKey],
  byId: Map[String, EncryptionKey]
) extends EncryptionKeySource:

  private def holding(key: EncryptionKey): Map[String, EncryptionKey] =
    require(
      byId.get(key.id.value).forall(_.sameMaterial(key)),
      s"two different encryption keys share the id '${key.id.value}'"
    )
    byId.updated(key.id.value, key)

  /** This source, encrypting `tenantId`'s payloads with `key` instead of the default. */
  def withTenantKey(tenantId: TenantId, key: EncryptionKey): StaticKeySource =
    new StaticKeySource(default, tenants.updated(tenantId.value, key), holding(key))

  /** This source, also reading what `key` wrote; nothing new is written with it unless it is also a current key. */
  def withRetiredKey(key: EncryptionKey): StaticKeySource = new StaticKeySource(default, tenants, holding(key))

  def current(tenantId: Option[TenantId]): Result[EncryptionKey] =
    tenantId.flatMap(t => tenants.get(t.value)).orElse(default).toRight(EncryptionError.NoCurrentKey(tenantId))

  def key(keyId: KeyId): Result[EncryptionKey] = byId.get(keyId.value).toRight(EncryptionError.UnknownKey(keyId))

  override def toString: String =
    val ids = byId.keys.toVector.sorted.mkString("[", ", ", "]")
    s"StaticKeySource(default=${default.fold("none")(_.id.value)}, tenants=${tenants.size}, keys=$ids)"

@Experimental
object StaticKeySource:

  /** `key` for every thread, of any tenant or none. */
  def apply(key: EncryptionKey): StaticKeySource =
    new StaticKeySource(Some(key), Map.empty, Map(key.id.value -> key))

  /**
   * No default key: only tenants given one with `withTenantKey` can write, and a thread of another tenant, or of
   * none, is refused ([[EncryptionError.NoCurrentKey]]).
   */
  val tenantsOnly: StaticKeySource = new StaticKeySource(None, Map.empty, Map.empty)
