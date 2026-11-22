package org.llm4s.agent.encryption

import org.llm4s.agent.graph.ThreadId
import org.llm4s.types.Result

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{ GCMParameterSpec, SecretKeySpec }
import scala.collection.mutable
import scala.util.Try

/**
 * The payloads of one thread, sealed and opened (#2449, spec 2026-11-22). Made for one call and used on one thread.
 *
 * A payload is a JSON value at a place in the thread - `place`, the parts that pin it there: what kind of row, which
 * checkpoint, which state key. It is stored as the envelope `{"llm4s:enc": "1.<key id>.<base64url(nonce ‖ ciphertext ‖
 * tag)>"}`: AES-256-GCM over the value's JSON bytes, with a fresh random 96-bit nonce, under the subkey the named key
 * derives for this thread, and with the key id, the thread id and `place` as associated data. A ciphertext therefore
 * opens only under its key, on its thread, at its place.
 */
final private[encryption] class ThreadCipher(
  threadId: ThreadId,
  keys: EncryptionKeySource,
  settings: EncryptionSettings
):
  import ThreadCipher.*

  private val subkeys = mutable.Map.empty[String, SecretKeySpec]

  private def subkey(key: EncryptionKey): SecretKeySpec =
    subkeys.getOrElseUpdate(key.id.value, key.subkey(SubkeyLabel ++ threadId.value.getBytes(UTF_8)))

  private def associated(keyId: KeyId, place: Vector[String]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    (Vector(Format, keyId.value, threadId.value) ++ place).foreach { part =>
      val bytes = part.getBytes(UTF_8)
      // length-prefixed, so no two lists of parts share an encoding
      out.write(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      out.write(bytes)
    }
    out.toByteArray

  /** `value` as an envelope under `key`, for `place`. */
  def seal(key: EncryptionKey, place: Vector[String], value: ujson.Value): Result[ujson.Value] =
    Try {
      val nonce = new Array[Byte](NonceBytes)
      random.nextBytes(nonce)
      val cipher = Cipher.getInstance(Transformation)
      cipher.init(Cipher.ENCRYPT_MODE, subkey(key), new GCMParameterSpec(TagBits, nonce))
      cipher.updateAAD(associated(key.id, place))
      val sealedBytes = cipher.doFinal(ujson.writeToByteArray(value))
      ujson.Obj(Marker -> s"$Format.${key.id.value}.${encoder.encodeToString(nonce ++ sealedBytes)}"): ujson.Value
    }.toEither.left.map(e => EncryptionError.EncryptionFailed(threadId, describe(place), e.getClass.getSimpleName))

  /**
   * The value the envelope `stored` holds for `place`. A value that is not an envelope is a plaintext: refused, or
   * copied out as it is when plaintext reads are on.
   */
  def open(place: Vector[String], stored: ujson.Value): Result[ujson.Value] =
    envelope(stored) match
      case None if settings.plaintextReads => Right(ujson.copy(stored))
      case None                            => Left(EncryptionError.NotEncrypted(threadId, describe(place)))
      case Some(text) =>
        val malformed = EncryptionError.MalformedCiphertext(threadId, describe(place))
        text.split('.') match
          case Array(Format, id, body) =>
            for
              keyId <- KeyId.of(id).left.map(_ => malformed)
              bytes <- Try(decoder.decode(body)).toOption
                .filter(_.length >= NonceBytes + TagBits / 8)
                .toRight(malformed)
              key <- keys.key(keyId)
              value <- Try {
                val cipher = Cipher.getInstance(Transformation)
                cipher.init(Cipher.DECRYPT_MODE, subkey(key), new GCMParameterSpec(TagBits, bytes, 0, NonceBytes))
                cipher.updateAAD(associated(keyId, place))
                ujson.read(cipher.doFinal(bytes, NonceBytes, bytes.length - NonceBytes))
              }.toEither.left.map(_ => EncryptionError.DecryptionFailed(threadId, describe(place), keyId))
            yield value
          case _ => Left(malformed)

private[encryption] object ThreadCipher:

  /** The one key of an envelope object. */
  val Marker = "llm4s:enc"

  /** The envelope format this build writes and reads; also the first part of the associated data. */
  private val Format = "1"

  private val Transformation = "AES/GCM/NoPadding"
  private val NonceBytes     = 12
  private val TagBits        = 128
  private val SubkeyLabel    = "llm4s-agent-store-v1\u0000".getBytes(UTF_8)

  private val random  = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  /** A seal or an open of a value at a place. */
  type Codec = (Vector[String], ujson.Value) => Result[ujson.Value]

  /** The envelope text of `stored`, if it is an envelope: an object of exactly the marker and a string. */
  def envelope(stored: ujson.Value): Option[String] = stored match
    case obj: ujson.Obj if obj.value.size == 1 => obj.value.get(Marker).flatMap(_.strOpt)
    case _                                     => None

  /** A place as an error names it: its kind and what it is of, never a payload. */
  private def describe(place: Vector[String]): String = place.filter(_.nonEmpty).mkString("/")

  /** `f` over each of `items` in turn, stopping at the first `Left`. */
  def traverse[A, B](items: Vector[A])(f: A => Result[B]): Result[Vector[B]] =
    items.foldLeft[Result[Vector[B]]](Right(Vector.empty))((done, item) => done.flatMap(acc => f(item).map(acc :+ _)))
