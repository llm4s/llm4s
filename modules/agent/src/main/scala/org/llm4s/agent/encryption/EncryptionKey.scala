package org.llm4s.agent.encryption

import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

import java.security.{ MessageDigest, SecureRandom }
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * The name of an [[EncryptionKey]], stored in plain with every ciphertext the key wrote so the reader knows which key
 * opens it (#2449): 1 to 64 of `A-Z a-z 0-9 _ -`. It names a key and is never derived from one.
 */
opaque type KeyId = String

@Experimental
object KeyId:
  private val Valid = "[A-Za-z0-9_-]{1,64}".r

  private def problem = "a key id is 1 to 64 characters of A-Z, a-z, 0-9, '_' and '-'"

  /** Throws `IllegalArgumentException` for an id that is not 1 to 64 of `A-Z a-z 0-9 _ -`; use [[of]] for untrusted input. */
  def apply(value: String): KeyId =
    require(Valid.matches(value), problem)
    value

  def of(value: String): Result[KeyId] =
    Either.cond(Valid.matches(value), value, EncryptionError.InvalidKey(problem))

  extension (id: KeyId) def value: String = id

/**
 * A 256-bit AES key and its [[KeyId]] (#2449). The key material is held privately: nothing reads it back, `toString`
 * prints the id and `***`, and no error names it. Two keys are equal only if they are the same instance.
 *
 * A key is never used directly. Each thread's payloads are encrypted under a subkey derived from it and the thread's
 * id (HKDF-Expand, RFC 5869, with HMAC-SHA-256), so the bound on random nonces under one AES-GCM key applies per
 * thread, not per store.
 */
@Experimental
final class EncryptionKey private (val id: KeyId, private val material: Array[Byte]):

  /** The AES-256 key for `context`: the first block of HKDF-Expand(material, context), which is 32 bytes. */
  private[encryption] def subkey(context: Array[Byte]): SecretKeySpec =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(material, "HmacSHA256"))
    mac.update(context)
    mac.update(1.toByte)
    new SecretKeySpec(mac.doFinal(), "AES")

  /** Whether `other` holds the same material; constant-time in the material. */
  private[encryption] def sameMaterial(other: EncryptionKey): Boolean =
    MessageDigest.isEqual(material, other.material)

  override def toString: String = s"EncryptionKey(${id.value}, ***)"

@Experimental
object EncryptionKey:

  /** The length of a key's material: 32 bytes, an AES-256 key. */
  val Bytes: Int = 32

  /** The key `id` over a copy of `material`, which must be [[Bytes]] long and should come from a CSPRNG or a KMS. */
  def of(id: KeyId, material: Array[Byte]): Result[EncryptionKey] =
    Either.cond(
      material.length == Bytes,
      new EncryptionKey(id, material.clone()),
      EncryptionError.InvalidKey(s"key '${id.value}' must be $Bytes bytes, was ${material.length}")
    )

  /** The key `id` from Base64 (standard or URL-safe alphabet, padded or not), as a secret store or a variable holds it. */
  def fromBase64(id: KeyId, encoded: String): Result[EncryptionKey] =
    val standard = encoded.trim.replace('-', '+').replace('_', '/')
    Try(Base64.getDecoder.decode(standard)).toEither.left
      // the decoder's message quotes the input: say only what is wrong
      .map(_ => EncryptionError.InvalidKey(s"key '${id.value}' is not Base64"))
      .flatMap(of(id, _))

  /**
   * A new random key `id`, from the JVM's `SecureRandom`. It exists only in this process: generate one in a test, or
   * to try the API.
   */
  def generate(id: KeyId): EncryptionKey =
    val material = new Array[Byte](Bytes)
    new SecureRandom().nextBytes(material)
    new EncryptionKey(id, material)
