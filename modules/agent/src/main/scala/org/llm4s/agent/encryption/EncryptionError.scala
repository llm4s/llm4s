package org.llm4s.agent.encryption

import org.llm4s.agent.graph.{ TenantId, ThreadId }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NonRecoverableError }

/**
 * Why a payload of the checkpoint store could not be encrypted or read (#2449). Every case is non-recoverable - the
 * same call fails the same way until the keys or the stored row change - and none carries key material, plaintext or
 * ciphertext: only which thread, which field and which key id.
 */
@Experimental
sealed trait EncryptionError extends LLMError

@Experimental
object EncryptionError:

  /** A key or key id that cannot be used; `reason` says why without quoting it. */
  final case class InvalidKey(reason: String) extends EncryptionError with NonRecoverableError:
    override val message: String = s"Invalid encryption key: $reason"

  /** The key source has no key to encrypt `tenantId`'s payloads with. Nothing was written. */
  final case class NoCurrentKey(tenantId: Option[TenantId]) extends EncryptionError with NonRecoverableError:
    override val message: String =
      s"No encryption key for ${tenantId.fold("threads with no tenant")(t => s"tenant '${t.value}'")}"

  /** A stored payload names key `keyId`, which the key source does not hold: a retired key removed too early, or another deployment's. */
  final case class UnknownKey(keyId: KeyId) extends EncryptionError with NonRecoverableError:
    override val message: String = s"No encryption key '${keyId.value}', which encrypted a stored payload"

  /**
   * `field` of thread `threadId` did not authenticate under key `keyId`: the key is not the one that wrote it, the
   * ciphertext was changed, or it was written for another thread, tenant or place and moved here.
   */
  final case class DecryptionFailed(threadId: ThreadId, field: String, keyId: KeyId)
      extends EncryptionError
      with NonRecoverableError:
    override val message: String =
      s"Cannot decrypt $field of thread '${threadId.value}' with key '${keyId.value}': " +
        "wrong key, or the stored value was changed or moved"

  /**
   * `field` of thread `threadId` is stored in plain and plaintext reads are off ([[EncryptionSettings.plaintextReads]]):
   * a row written before encryption was switched on, or one put there since.
   */
  final case class NotEncrypted(threadId: ThreadId, field: String) extends EncryptionError with NonRecoverableError:
    override val message: String =
      s"$field of thread '${threadId.value}' is stored unencrypted, and plaintext reads are off"

  /** `field` of thread `threadId` is marked encrypted but is not a ciphertext this build reads: damaged, or a newer format. */
  final case class MalformedCiphertext(threadId: ThreadId, field: String)
      extends EncryptionError
      with NonRecoverableError:
    override val message: String = s"$field of thread '${threadId.value}' is not a ciphertext this build reads"

  /** The JVM refused to encrypt `field` of thread `threadId` (`cause` is the exception's class): nothing was written. */
  final case class EncryptionFailed(threadId: ThreadId, field: String, cause: String)
      extends EncryptionError
      with NonRecoverableError:
    override val message: String = s"Cannot encrypt $field of thread '${threadId.value}': $cause"
