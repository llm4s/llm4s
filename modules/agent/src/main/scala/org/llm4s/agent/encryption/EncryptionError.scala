package org.llm4s.agent.encryption

import org.llm4s.agent.graph.{ TenantId, ThreadId }
import org.llm4s.agent.harness.{ Namespace, Text }
import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError

/**
 * Why a payload of the checkpoint store, its run queue or the long-term store could not be encrypted or read (#2449).
 * Every case is non-recoverable - the same call fails the same way until the keys or the stored row change - and none
 * carries key material, plaintext or ciphertext: only what the payload belongs to ([[EncryptionError.Owner]]), which
 * field of it and which key id.
 */
@Experimental
sealed trait EncryptionError extends LLMError

@Experimental
object EncryptionError:

  /** What a stored payload belongs to. */
  enum Owner:

    /** A thread of the checkpoint store or its run queue. */
    case Thread(threadId: ThreadId)

    /** The item at `key` of a long-term store's namespace. */
    case Item(namespace: Namespace, key: String)

    /** The owner as a message names it: ids the store holds in plain, a long key cut short. */
    def describe: String = this match
      case Thread(threadId)     => s"thread '${threadId.value}'"
      case Item(namespace, key) => s"item '${Text.quoted(key)}' of namespace ${namespace.render}"

  /** A key or key id that cannot be used; `reason` says why without quoting it. */
  final case class InvalidKey(reason: String) extends EncryptionError:
    override val message: String = s"Invalid encryption key: $reason"

  /** The key source has no key to encrypt `tenantId`'s payloads with. Nothing was written. */
  final case class NoCurrentKey(tenantId: Option[TenantId]) extends EncryptionError:
    override val message: String =
      s"No encryption key for ${tenantId.fold("payloads with no tenant")(t => s"tenant '${t.value}'")}"

  /** A stored payload names key `keyId`, which the key source does not hold: a retired key removed too early, or another deployment's. */
  final case class UnknownKey(keyId: KeyId) extends EncryptionError:
    override val message: String = s"No encryption key '${keyId.value}', which encrypted a stored payload"

  /**
   * `field` of `owner` did not authenticate under key `keyId`: the key is not the one that wrote it, the ciphertext
   * was changed, or it was written for another thread, namespace, item, tenant or place and moved here.
   */
  final case class DecryptionFailed(owner: Owner, field: String, keyId: KeyId)
      extends EncryptionError:
    override val message: String =
      s"Cannot decrypt $field of ${owner.describe} with key '${keyId.value}': " +
        "wrong key, or the stored value was changed or moved"

  /**
   * `field` of `owner` is stored in plain and plaintext reads are off ([[EncryptionSettings.plaintextReads]]): a row
   * written before encryption was switched on, or one put there since.
   */
  final case class NotEncrypted(owner: Owner, field: String) extends EncryptionError:
    override val message: String =
      s"$field of ${owner.describe} is stored unencrypted, and plaintext reads are off"

  /** `field` of `owner` is marked encrypted but is not a ciphertext this build reads: damaged, or a newer format. */
  final case class MalformedCiphertext(owner: Owner, field: String) extends EncryptionError:
    override val message: String = s"$field of ${owner.describe} is not a ciphertext this build reads"

  /** The JVM refused to encrypt `field` of `owner` (`cause` is the exception's class): nothing was written. */
  final case class EncryptionFailed(owner: Owner, field: String, cause: String)
      extends EncryptionError:
    override val message: String = s"Cannot encrypt $field of ${owner.describe}: $cause"
