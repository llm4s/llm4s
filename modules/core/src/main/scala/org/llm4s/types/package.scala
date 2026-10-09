package org.llm4s

import org.llm4s.annotation.Stable
import org.llm4s.core.safety.{ DefaultErrorMapper, ErrorMapper, Safety }
import org.llm4s.types.TryOps
import org.llm4s.types.{ AsyncResult, Result }
import upickle.default.{ readwriter, ReadWriter => RW }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
 * The `Result` type, its syntax, and the newtypes the library's own APIs use.
 */
package object types {

  /** Standard synchronous result type used throughout the library */
  type Result[+A] = Either[error.LLMError, A]

  /** Async result type for Future-based operations */
  type AsyncResult[+A] = Future[Result[A]]

  /**
   * Type-safe wrapper for session IDs
   */
  final case class SessionId(value: String) extends AnyVal {
    override def toString: String = value
  }

  object SessionId {
    implicit val rw: RW[SessionId] =
      readwriter[String].bimap[SessionId](_.value, SessionId.apply)
  }

  /**
   * Type-safe wrapper for trace IDs for distributed tracing
   */
  final case class TraceId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type-safe wrapper for file paths */
  final case class FilePath(value: String) extends AnyVal {
    override def toString: String = value
    def extension: Option[String] = {
      val lastDot = value.lastIndexOf('.')
      if (lastDot >= 0) Some(value.substring(lastDot + 1)) else None
    }
  }

  object FilePath {
    implicit val rw: RW[FilePath] =
      readwriter[String].bimap[FilePath](_.value, FilePath.apply)
  }

  /** Type-safe wrapper for directory paths */
  final case class DirectoryPath(value: String) extends AnyVal {
    override def toString: String = value
  }

  object DirectoryPath {
    implicit val rw: RW[DirectoryPath] =
      readwriter[String].bimap[DirectoryPath](_.value, DirectoryPath.apply)
  }

  // Context Management Types (for conversation context handling)
  final case class SemanticBlockId(value: String) extends AnyVal {
    override def toString: String = value
  }

  /** Type alias for externalization threshold in bytes */
  type ExternalizationThreshold = Long

  /** Type-safe wrapper for artifact store keys */
  final case class ArtifactKey(value: String) extends AnyVal {
    override def toString: String = value
  }

  object ArtifactKey {
    def generate(): ArtifactKey =
      ArtifactKey(java.util.UUID.randomUUID().toString)

    def fromContent(content: String): ArtifactKey = {
      val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(content.getBytes("UTF-8"))
        .map("%02x".format(_))
        .mkString
      ArtifactKey(s"content_$hash")
    }
  }

  /** Information about externalized content */
  case class ExternalizedContent(
    key: ArtifactKey,
    originalSize: ByteCount,
    contentType: String,
    summary: String
  )

  object SemanticBlockId {
    def generate(): SemanticBlockId =
      SemanticBlockId(java.util.UUID.randomUUID().toString.take(8))
  }

  /** Type alias for context window size in semantic blocks */
  type ContextWindowSize = Int

  /** Type-safe wrapper for content size in bytes with validation */
  final case class ContentSize(bytes: Long) extends AnyVal {
    override def toString: String                                      = s"${bytes}B"
    def toKB: Double                                                   = bytes / 1024.0
    def toMB: Double                                                   = bytes / (1024.0 * 1024.0)
    def exceedsThreshold(threshold: ExternalizationThreshold): Boolean = bytes > threshold
  }

  object ContentSize {
    def fromString(content: String): ContentSize =
      ContentSize(content.getBytes("UTF-8").length.toLong)

    def fromBytes(bytes: Array[Byte]): ContentSize =
      ContentSize(bytes.length.toLong)
  }

  /** Type alias for byte count */
  type ByteCount = Long

  /** Type alias for token budget */
  type TokenBudget = Int

  /** Type-safe wrapper for headroom percentage with validation */
  final case class HeadroomPercent(value: Double) extends AnyVal {
    override def toString: String = f"${value * 100}%.1f%%"
    def asRatio: Double           = value
    def isValid: Boolean          = value >= 0.0 && value < 1.0
  }

  object HeadroomPercent {
    def create(value: Double): Result[HeadroomPercent] =
      value match {
        case v if v >= 0.0 && v < 1.0 => Right(HeadroomPercent(v))
        case v => Left(error.ValidationError(s"Invalid headroom: $v. Must be between 0.0 and 1.0", "headroom"))
      }

    val None: HeadroomPercent         = HeadroomPercent(0.0)
    val Light: HeadroomPercent        = HeadroomPercent(0.05) // 5%
    val Standard: HeadroomPercent     = HeadroomPercent(0.08) // 8%
    val Conservative: HeadroomPercent = HeadroomPercent(0.15) // 15%
  }

  /** Syntax: Try/Option/Future to Result conversions */
  implicit final class TryOps[A](val t: Try[A]) extends AnyVal {
    def toResult(implicit em: ErrorMapper = DefaultErrorMapper): Result[A] =
      t.fold(e => Left(em(e)), v => Right(v))
  }

  implicit final class OptionOps[A](val oa: Option[A]) extends AnyVal {
    def toResult(ifEmpty: => error.LLMError): Result[A] = oa.toRight(ifEmpty)
  }

  implicit final class FutureOps[A](val fa: Future[A]) extends AnyVal {
    def toResult(implicit ec: ExecutionContext, em: ErrorMapper = DefaultErrorMapper): Future[Result[A]] =
      Safety.future.fromFuture(fa)
  }

}

/**
 * Companion object for Result types.
 * Provides utility methods for creating and manipulating Result types.
 * Includes methods for success, failure, fromOption, sequence, traverse, and combine.
 * These methods allow users to easily create and handle Result types,
 * providing a consistent and type-safe way to work with results in the LLM4S.
 * Provides a clear and concise API for error handling and result manipulation.
 * Provides methods for creating success and failure results,
 * converting from Option types, sequencing and traversing lists of results,
 * and combining two results into a tuple.
 * This object is designed to be extensible for future requirements,
 * allowing users to add additional utility methods as needed.
 * It provides a consistent and type-safe way to handle results and errors in the LLM4S.
 */

@Stable
object Result {
  def success[A](value: A): Result[A]                        = Right(value)
  def failure[A](error: org.llm4s.error.LLMError): Result[A] = Left(error)

  def fromOption[A](opt: Option[A], error: => org.llm4s.error.LLMError): Result[A] =
    opt.toRight(error)

  /**
   * Collects a list of results into a result of a list: the values in order, or the first
   * `Left` in the list.
   */
  def sequence[A](results: List[Result[A]]): Result[List[A]] =
    traverse(results)(result => result)

  /**
   * Applies `f` to each element in order and collects the values, stopping at the first `Left`:
   * `f` is not called on any element after the first failure, so side effects and expensive work
   * in `f` stop there too.
   */
  def traverse[A, B](list: List[A])(f: A => Result[B]): Result[List[B]] = {
    @scala.annotation.tailrec
    def loop(remaining: List[A], acc: List[B]): Result[List[B]] =
      remaining match {
        case Nil => Right(acc.reverse)
        case head :: tail =>
          f(head) match {
            case Right(value) => loop(tail, value :: acc)
            case Left(error)  => Left(error)
          }
      }
    loop(list, Nil)
  }

  // Combinators for multiple Results
  def combine[A, B](ra: Result[A], rb: Result[B]): Result[(A, B)] =
    for {
      a <- ra
      b <- rb
    } yield (a, b)

  def combine[A, B, C](ra: Result[A], rb: Result[B], rc: Result[C]): Result[(A, B, C)] =
    for {
      a <- ra
      b <- rb
      c <- rc
    } yield (a, b, c)

  def safely[A](operation: => A): Result[A] = Try(operation).toResult

  // Async support
  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): Future[Result[A]] =
    future.map(success).recover { case ex => failure(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  /**
   * Create Result from boolean condition
   */
  def fromBoolean(condition: Boolean, error: org.llm4s.error.LLMError): Result[Unit] =
    if (condition) success(()) else failure(error)

  /**
   * Create Result from boolean with custom success value
   */
  def fromBooleanWithValue[A](condition: Boolean, successValue: A, error: org.llm4s.error.LLMError): Result[A] =
    if (condition) success(successValue) else failure(error)

  // Validation that collects all errors
  def validateAll[A](items: List[A])(validator: A => Result[A]): Either[List[error.LLMError], List[A]] = {
    val results   = items.map(validator)
    val errors    = results.collect { case Left(error) => error }
    val successes = results.collect { case Right(value) => value }
    if (errors.nonEmpty) Left(errors) else Right(successes)
  }

  // Resource management: use scala.util.Using + types.TryOps#toResult
}

@Stable
object AsyncResult {
  import scala.concurrent.ExecutionContext

  def success[A](value: A): AsyncResult[A]                        = Future.successful(Right(value))
  def failure[A](error: org.llm4s.error.LLMError): AsyncResult[A] = Future.successful(Left(error))

  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): AsyncResult[A] =
    future.map(Right(_)).recover { case ex => Left(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  def fromResult[A](result: Result[A]): AsyncResult[A] =
    Future.successful(result)
}
