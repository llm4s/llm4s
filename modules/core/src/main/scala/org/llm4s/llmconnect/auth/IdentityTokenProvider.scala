package org.llm4s.llmconnect.auth

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.error.AuthenticationError
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.nio.file.attribute.BasicFileAttributes
import scala.util.{ Try, Using }

/**
 * Where a workload's identity token comes from - typically a SPIFFE JWT-SVID file that
 * `spiffe-helper` keeps fresh. The configured form, before it is read: [[IdentityTokenProvider.from]] makes the
 * [[IdentityTokenProvider]] that reads it. Stable since #1818, as part of the
 * workload-identity configuration the Stable configs carry.
 */
@Stable
enum IdentitySource:
  /** A file re-read on every fetch, so a rotated token is picked up. */
  case File(path: Path)

  /** A fixed token: for tests, or a token injected some other way. A `Secret`, so it stays out of logs. */
  case Literal(token: Secret)

  override def toString: String = this match
    case File(path) => s"File($path)"
    case Literal(_) => "Literal(***)"

/**
 * Supplies the subject token a token exchange presents: the workload's identity, read afresh for each exchange.
 *
 * Named and shaped as its siblings [[ApiKeyProvider]] and [[AccessTokenProvider]] are: one method, `current()`.
 * [[IdentityTokenProvider.from]] makes one from a configured [[IdentitySource]].
 */
@Experimental
trait IdentityTokenProvider:

  /** The identity token to present now. */
  def current(): Result[Secret]

@Experimental
object IdentityTokenProvider:

  private val Provider = ProviderId("workload-identity")

  /**
   * The most bytes an identity token file may hold. A JWT-SVID or a projected service-account token is a few
   * kilobytes; a file larger than this is not a token, and reading it whole would let a misconfigured path
   * (a log file, a device) exhaust the heap on every exchange.
   */
  private[llm4s] val MaxTokenFileBytes: Int = 64 * 1024

  /**
   * Reads `path` on every fetch, trimmed; a missing, unreadable, blank or oversized file, or a path that is
   * not a regular file, is an `AuthenticationError`. Symbolic links are followed, as Kubernetes projected
   * volumes and `spiffe-helper` rotate a token by swapping a link; the target is checked to be a regular
   * file before it is opened, so a FIFO or a device is never opened (and cannot block a fetch), and the
   * read itself stops after [[MaxTokenFileBytes]] + 1 bytes, so even a file swapped between the check and
   * the open cannot be read unbounded. Neither the file's content nor any part of it is put in an error.
   */
  def file(path: Path): IdentityTokenProvider = () =>
    readBounded(path).flatMap(raw => nonBlank(raw, s"identity token file $path is empty")).map(Secret.unregistered)

  private def readBounded(path: Path): Result[String] =
    def failure(reason: String) = AuthenticationError(Provider, s"cannot read identity token file $path: $reason")
    Try(Files.readAttributes(path, classOf[BasicFileAttributes])).toEither.left
      .map(e => failure(e.getClass.getSimpleName))
      .flatMap { attributes =>
        if !attributes.isRegularFile then Left(failure("not a regular file"))
        else if attributes.size > MaxTokenFileBytes then
          Left(failure(s"larger than $MaxTokenFileBytes bytes, so not an identity token"))
        else
          Try(Using.resource(Files.newInputStream(path))(_.readNBytes(MaxTokenFileBytes + 1))).toEither.left
            .map(e => failure(e.getClass.getSimpleName))
            .flatMap { bytes =>
              if bytes.length > MaxTokenFileBytes then
                Left(failure(s"larger than $MaxTokenFileBytes bytes, so not an identity token"))
              else Right(new String(bytes, StandardCharsets.UTF_8))
            }
      }

  /** Always returns `token`, trimmed; a blank token is an `AuthenticationError`. */
  def static(token: Secret): IdentityTokenProvider = () =>
    nonBlank(token.reveal, "identity token is empty").map(trimmed =>
      if trimmed == token.reveal then token else Secret(trimmed)
    )

  def from(source: IdentitySource): IdentityTokenProvider = source match
    case IdentitySource.File(path)     => file(path)
    case IdentitySource.Literal(token) => static(token)

  private def nonBlank(raw: String, whenBlank: String): Result[String] =
    val token = raw.trim
    if token.isEmpty then Left(AuthenticationError(Provider, whenBlank)) else Right(token)
