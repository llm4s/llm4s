package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{ BasicFileAttributes, FileTime }
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicReference
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
 * `OpenAICompatibleConfig`) carry one.
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
   * A missing or unreadable file, one that is not a regular file, a blank one, one over 64 KiB or one holding
   * more than one line is a `ConfigurationError` naming the path; the file's content is never put in an
   * error or a log.
   */
  def file(path: Path): ApiKeyProvider = new KeyFile(path)

  final private class Static(key: ApiKey) extends ApiKeyProvider:
    override def current(): Result[ApiKey] = Right(key)
    override def toString: String          = "ApiKeyProvider.static(***)"

  /** The file's state when it was last read: a key is reused while the file still has it. */
  final private case class Stamp(modified: FileTime, size: Long, identity: Option[AnyRef])

  final private case class Read(stamp: Stamp, key: ApiKey)

  final private class KeyFile(path: Path) extends ApiKeyProvider:
    private val last = new AtomicReference[Option[Read]](None)

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
            val key = new String(bytes, StandardCharsets.UTF_8).trim
            if bytes.length > MaxKeyFileBytes then Left(failure(s"is larger than $MaxKeyFileBytes bytes"))
            else if key.isEmpty then Left(failure("is empty"))
            // A line break in a header value would be refused by the HTTP client, or split the header.
            else if key.exists(c => c == '\n' || c == '\r') then Left(failure("holds more than one line"))
            else Right(ApiKey(key))
          }

    override def toString: String = s"ApiKeyProvider.file($path)"
