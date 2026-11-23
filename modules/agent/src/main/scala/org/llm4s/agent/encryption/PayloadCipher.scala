package org.llm4s.agent.encryption

import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.harness.Namespace
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
 * The payloads of one scope - a thread of the checkpoint store, or a namespace of the long-term store - sealed and
 * opened (#2449, spec 2026-11-22). Made for one call and used on one thread.
 *
 * A payload is a JSON value at a place in its scope - `place`, the parts that pin it there: what kind of row, which
 * checkpoint, which state key; or which item. It is stored as the envelope `{"llm4s:enc": "1.<key id>.<base64url(nonce
 * ‖ ciphertext ‖ tag)>"}`: AES-256-GCM over the value's JSON bytes, with a fresh random 96-bit nonce, under the subkey
 * the named key derives for this scope, and with the key id, the scope and `place` as associated data. A ciphertext
 * therefore opens only under its key, in its scope, at its place.
 *
 * @param context what a key's subkey is derived for: a label of the kind of scope, then the scope
 * @param scope the scope as the associated data names it
 * @param named what an error says the payload at a place belongs to, and which field of it the payload is
 */
final private[encryption] class PayloadCipher private (
  context: Array[Byte],
  scope: String,
  keys: EncryptionKeySource,
  settings: EncryptionSettings,
  named: Vector[String] => (EncryptionError.Owner, String)
):
  import PayloadCipher.*

  private val subkeys = mutable.Map.empty[String, SecretKeySpec]

  private def subkey(key: EncryptionKey): SecretKeySpec =
    subkeys.getOrElseUpdate(key.id.value, key.subkey(context))

  private def associated(keyId: KeyId, place: Vector[String]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    (Vector(Format, keyId.value, scope) ++ place).foreach { part =>
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
      val sealedBytes = cipher.doFinal(plaintext(value))
      ujson.Obj(Marker -> s"$Format.${key.id.value}.${encoder.encodeToString(nonce ++ sealedBytes)}"): ujson.Value
    }.toEither.left.map { e =>
      val (owner, field) = named(place)
      EncryptionError.EncryptionFailed(owner, field, e.getClass.getSimpleName)
    }

  /**
   * The value the envelope `stored` holds for `place`. A value that is not an envelope is a plaintext: refused, or
   * copied out as it is when plaintext reads are on.
   */
  def open(place: Vector[String], stored: ujson.Value): Result[ujson.Value] =
    lazy val (owner, field) = named(place)
    envelope(stored) match
      case None if settings.plaintextReads => Right(ujson.copy(stored))
      case None                            => Left(EncryptionError.NotEncrypted(owner, field))
      case Some(text) =>
        lazy val malformed = EncryptionError.MalformedCiphertext(owner, field)
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
                parsed(cipher.doFinal(bytes, NonceBytes, bytes.length - NonceBytes))
              }.toEither.left.map(_ => EncryptionError.DecryptionFailed(owner, field, keyId))
            yield value
          case _ => Left(malformed)

private[encryption] object PayloadCipher:

  /** The one key of an envelope object. */
  val Marker = "llm4s:enc"

  /** The envelope format this build writes and reads; also the first part of the associated data. */
  private val Format = "1"

  private val Transformation = "AES/GCM/NoPadding"
  private val NonceBytes     = 12
  private val TagBits        = 128
  private val ThreadsLabel   = "llm4s-agent-store-v1\u0000".getBytes(UTF_8)
  private val ItemsLabel     = "llm4s-agent-items-v1\u0000".getBytes(UTF_8)

  private val random  = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  /** The longest a key id is, in characters: `KeyId`'s bound. */
  val MaxKeyIdChars = 64

  /** The cipher of `threadId`'s payloads: an error names the thread, and the place as the field. */
  def ofThread(threadId: ThreadId, keys: EncryptionKeySource, settings: EncryptionSettings): PayloadCipher =
    new PayloadCipher(
      ThreadsLabel ++ threadId.value.getBytes(UTF_8),
      threadId.value,
      keys,
      settings,
      place => (EncryptionError.Owner.Thread(threadId), describe(place))
    )

  /**
   * The cipher of the long-term store's namespace `ns`, its tenant included, under a subkey of a kind of its own: no
   * thread's id derives it. Its places are `item` and `outcome`.
   */
  def ofNamespace(ns: Namespace, keys: EncryptionKeySource, settings: EncryptionSettings): PayloadCipher =
    // tenant first, then the path, in ASCII: `Namespace.render` is UTF-8, in which two unpaired surrogates are alike
    val rendered = ujson.write(
      ujson.Arr.from(ns.tenant.fold[ujson.Value](ujson.Null)(t => ujson.Str(t.value)) +: ns.path.map(ujson.Str(_))),
      escapeUnicode = true
    )
    new PayloadCipher(
      ItemsLabel ++ rendered.getBytes(UTF_8),
      rendered,
      keys,
      settings,
      place => (EncryptionError.Owner.Item(ns, place.lift(1).getOrElse("")), place.headOption.getOrElse(""))
    )

  /** The place of the value of the item at `key`. */
  def item(key: String): Vector[String] = Vector("value", key)

  /** The place of the outcome recorded under `effect`, an effect key's value, for a change to the item at `key`. */
  def outcome(key: String, effect: String): Vector[String] = Vector("outcome", key, effect)

  /**
   * `value` as the bytes that are sealed: its compact JSON, written straight to UTF-8. A string holding an unpaired
   * surrogate has no UTF-8, so a value with one is written as text instead, with every character past ASCII escaped,
   * after one space: JSON still, and what tells [[parsed]] to read it as text.
   */
  private def plaintext(value: ujson.Value): Array[Byte] =
    if unpaired(value) then (" " + ujson.write(value, escapeUnicode = true)).getBytes(UTF_8)
    else ujson.writeToByteArray(value)

  /**
   * The value an authenticated plaintext holds. It is parsed from its bytes, unless it starts with the space
   * [[plaintext]] writes before a value with an unpaired surrogate: the byte parser decodes an escape into UTF-8,
   * which has no such character, so that one is parsed as text.
   */
  private def parsed(plain: Array[Byte]): ujson.Value =
    if plain.nonEmpty && plain(0) == ' ' then ujson.read(new String(plain, UTF_8)) else ujson.read(plain)

  /** Whether a string of `value`, a key included, holds a surrogate that is not half of a pair. */
  private def unpaired(value: ujson.Value): Boolean = value match
    case ujson.Str(s) => unpaired(s)
    case ujson.Arr(a) => a.exists(unpaired)
    case ujson.Obj(o) => o.keysIterator.exists(unpaired) || o.valuesIterator.exists(unpaired)
    case _            => false

  private def unpaired(s: String): Boolean =
    var i     = 0
    var found = false
    while !found && i < s.length do
      val c = s.charAt(i)
      if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) then i += 2
      else if Character.isSurrogate(c) then found = true
      else i += 1
    found

  /** The JSON bytes of an envelope around its Base64: `{"llm4s:enc":"1.<key id>.<body>"}`. */
  private def around(keyIdChars: Int): Long = Marker.length + 5L + Format.length + 1L + keyIdChars + 1L + 2L

  /** What is sealed beside the plaintext: the nonce and the tag. */
  private val Beside: Long = NonceBytes + TagBits / 8L

  /**
   * The JSON bytes of an envelope that seals `plain` bytes under a key id of `keyIdChars` characters: the marker and
   * the format, the key id, and the nonce, ciphertext and tag in unpadded Base64.
   */
  def sealedBytes(plain: Long, keyIdChars: Int): Long = around(keyIdChars) + (4L * (plain + Beside) + 2L) / 3L

  /** The most bytes an envelope of at most `stored` JSON bytes seals, under a key id of `keyIdChars` characters. */
  def sealableBytes(stored: Long, keyIdChars: Int): Long = (stored - around(keyIdChars)) * 3L / 4L - Beside

  /**
   * How many bytes the envelope `stored` seals, read from its length without opening it; `None` for a value that is
   * not an envelope this build writes.
   */
  def plainBytes(stored: ujson.Value): Option[Long] =
    envelope(stored).map(_.split('.')).collect {
      case Array(Format, _, body) if body.length * 3L / 4L >= Beside => body.length * 3L / 4L - Beside
    }

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
