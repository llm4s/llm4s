package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental
import org.llm4s.error.AuthenticationError
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

/**
 * Where a workload's identity token comes from - typically a SPIFFE JWT-SVID file that
 * `spiffe-helper` keeps fresh. The configured form, before it is read.
 */
@Experimental
enum IdentitySource:
  /** A file re-read on every fetch, so a rotated token is picked up. */
  case File(path: Path)

  /** A fixed token: for tests, or a token injected some other way. */
  case Literal(token: String)

  override def toString: String = this match
    case File(path) => s"File($path)"
    case Literal(_) => "Literal(***)"

/** Fetches the subject token a token exchange presents. */
@Experimental
trait IdentityTokenSource:
  def fetch(): Result[String]

object IdentityTokenSource:

  private val Provider = "workload-identity"

  /** Reads `path` on every fetch, trimmed; a missing, unreadable or blank file is an `AuthenticationError`. */
  def file(path: Path): IdentityTokenSource = () =>
    Try(Files.readString(path, StandardCharsets.UTF_8)).toEither.left
      .map(e => AuthenticationError(Provider, s"cannot read identity token file $path: ${e.getClass.getSimpleName}"))
      .flatMap(raw => nonBlank(raw, s"identity token file $path is empty"))

  /** Always returns `token`, trimmed; a blank token is an `AuthenticationError`. */
  def static(token: String): IdentityTokenSource = () => nonBlank(token, "identity token is empty")

  def from(source: IdentitySource): IdentityTokenSource = source match
    case IdentitySource.File(path)     => file(path)
    case IdentitySource.Literal(token) => static(token)

  private def nonBlank(raw: String, whenBlank: String): Result[String] =
    val token = raw.trim
    if token.isEmpty then Left(AuthenticationError(Provider, whenBlank)) else Right(token)
