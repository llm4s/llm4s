package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

import java.nio.file.attribute.{ BasicFileAttributes, FileTime, PosixFilePermission }
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * Supplies the API key a client sends, asked once per request, so a key rotated by a secret manager is used
 * without rebuilding the client (#2313).
 *
 * A config carries one as `ProviderConfig.apiKeyProvider`: a section's `apiKeyFile` sets
 * [[ApiKeyProvider.file]], and code sets any implementation with `withApiKeyProvider` - one backed by Vault,
 * AWS Secrets Manager or another secret store, which llm4s does not depend on. `current()` is called on the
 * request path, so an implementation caches and fetches again only when the key may have changed. It
 * returns a failure as a `Left` rather than throwing; a client turns a thrown exception into a failed
 * request all the same.
 *
 * The key is an [[org.llm4s.types.ProviderModelTypes.ApiKey ApiKey]], so creating one registers it for
 * redaction: an error that echoes a rotated key is scrubbed of it too.
 *
 * Stable, as [[AccessTokenProvider]] is: the Stable configs that support it (`OpenAIConfig`, `AnthropicConfig`,
 * `OpenAICompatibleConfig`, `AzureConfig`, `GeminiConfig`, `DeepSeekConfig`, `ZaiConfig`) carry one, as do the
 * Mistral, Cohere, xAI and watsonx configs (#2477).
 */
@Stable
trait ApiKeyProvider:
  /** The key to send with the next request. */
  def current(): Result[ApiKey]

object ApiKeyProvider:

  /**
   * The most bytes an API key file may hold. A key is at most a few hundred bytes; a larger file is not a key,
   * and reading it whole on every change would let a misconfigured path exhaust the heap.
   */
  private[llm4s] val MaxKeyFileBytes: Int = 64 * 1024

  /** Always `key`. */
  def static(key: ApiKey): ApiKeyProvider = new Static(key)

  /**
   * The trimmed content of `path`, read again whenever the file changes: each `current()` checks the file's
   * modification time, size and identity (one `stat`) and re-reads it only when one of them differs from
   * the last read. A key that Vault Agent, the Secrets Store CSI driver or a Kubernetes secret volume
   * rewrites - in place, or by swapping a symbolic link - is therefore sent from the next request on.
   *
   * A leading byte-order mark, which Windows editors write, is dropped. A missing or unreadable file, one that is
   * not a regular file, a blank one, one over 64 KiB, a UTF-16 one, one holding more than one line, or one holding a
   * control character other than a tab or a character above U+00FF is a `ConfigurationError` naming the path; the
   * file's content is never put in an error or a log.
   */
  def file(path: Path): ApiKeyProvider = new KeyFile(path)

  /**
   * `raw` trimmed, without a leading byte-order mark (U+FEFF, which `trim` keeps), and trimmed again, so a BOM
   * after leading whitespace is dropped too: the key text every key source sends (#3109).
   */
  private[llm4s] def keyText(raw: String): String = raw.trim.stripPrefix("\uFEFF").trim

  /**
   * Why `key` cannot be sent in an HTTP header, or `None`. A line break, a control character other than a tab, or
   * a character above U+00FF is refused by every HTTP client llm4s sends through (the JDK `HttpClient` and the
   * OkHttp client of the OpenAI and Anthropic SDKs), on every request and with an error that names no key source,
   * so each key source refuses it when it reads the key (#3109). A tab and a Latin-1 character (U+0080 to U+00FF)
   * are left alone: the JDK client sends them. The reason names the kind of character and its position in the
   * trimmed key, never the key.
   */
  private[llm4s] def keyFault(key: String): Option[String] =
    // A line break in a header value would be refused by the HTTP client, or split the header.
    if key.exists(c => c == '\n' || c == '\r') then Some("holds more than one line")
    else
      val at = key.indexWhere(c => (c < ' ' && c != '\t') || c == '\u007f' || c > '\u00ff')
      Option.when(at >= 0) {
        val kind = if key(at) > '\u00ff' then "a character above U+00FF" else "a control character"
        s"holds $kind at position ${at + 1} of the trimmed key, which no HTTP header can carry"
      }

  private val logger = LoggerFactory.getLogger(getClass)

  private val SharedRead = Set(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)

  final private class Static(key: ApiKey) extends ApiKeyProvider:
    override def current(): Result[ApiKey] = Right(key)
    override def toString: String          = "ApiKeyProvider.static(***)"

  /** The file's state when it was last read: a key is reused while the file still has it. */
  final private case class Stamp(modified: FileTime, size: Long, identity: Option[AnyRef])

  final private case class Read(stamp: Stamp, key: ApiKey)

  /**
   * Whether `bytes` start with a UTF-16 byte-order mark (FF FE or FE FF), as Windows PowerShell 5's `>` and
   * `Out-File` write. Read as UTF-8 such a file is mojibake, so it is refused with a reason that says how to fix it.
   */
  private def isUtf16(bytes: Array[Byte]): Boolean =
    bytes.length >= 2 && {
      val (a, b) = (bytes(0) & 0xff, bytes(1) & 0xff)
      (a == 0xff && b == 0xfe) || (a == 0xfe && b == 0xff)
    }

  final private class KeyFile(path: Path) extends ApiKeyProvider:
    private val last         = new AtomicReference[Option[Read]](None)
    private val warnedAccess = new AtomicBoolean(false)

    private def failure(reason: String): ConfigurationError =
      ConfigurationError(s"API key file $path $reason")

    override def current(): Result[ApiKey] =
      Try(Files.readAttributes(path, classOf[BasicFileAttributes])).toEither.left
        .map(e => failure(s"cannot be read (${e.getClass.getSimpleName})"))
        .flatMap { attributes =>
          val stamp = Stamp(attributes.lastModifiedTime, attributes.size, Option(attributes.fileKey))
          last.get() match
            case Some(read) if read.stamp == stamp => Right(read.key)
            case _                                 =>
              // The stamp is taken before the read, so a change between the two is read again next time.
              read(attributes).map { key =>
                last.set(Some(Read(stamp, key)))
                key
              }
        }

    private def read(attributes: BasicFileAttributes): Result[ApiKey] =
      if !attributes.isRegularFile then Left(failure("is not a regular file"))
      else if attributes.size > MaxKeyFileBytes then Left(failure(s"is larger than $MaxKeyFileBytes bytes"))
      else
        Try(Using.resource(Files.newInputStream(path))(_.readNBytes(MaxKeyFileBytes + 1))).toEither.left
          .map(e => failure(s"cannot be read (${e.getClass.getSimpleName})"))
          .flatMap { bytes =>
            lazy val key = keyText(new String(bytes, StandardCharsets.UTF_8))
            if bytes.length > MaxKeyFileBytes then Left(failure(s"is larger than $MaxKeyFileBytes bytes"))
            else if isUtf16(bytes) then Left(failure("is UTF-16 encoded; save it as UTF-8"))
            else if key.isEmpty then Left(failure("is empty"))
            else
              keyFault(key) match
                case Some(reason) => Left(failure(reason))
                case None =>
                  warnIfShared()
                  Right(ApiKey(key))
          }

    /**
     * One WARN, naming the path, when the file is readable by its group or by others on a POSIX file system. A
     * Kubernetes secret volume defaults to mode 0644; `defaultMode: 0400` keeps the key to its owner.
     */
    private def warnIfShared(): Unit =
      Try(Files.getPosixFilePermissions(path).asScala).toOption
        .filter(perms => perms.exists(SharedRead.contains))
        .foreach(_ =>
          if warnedAccess.compareAndSet(false, true) then
            logger.warn(s"API key file $path is readable by its group or by others; restrict it to its owner (0400)")
        )

    override def toString: String = s"ApiKeyProvider.file($path)"
