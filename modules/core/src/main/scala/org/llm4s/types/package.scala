package org.llm4s

import scala.concurrent.Future

/**
 * The `Result` type, its helpers (the `Result` object), and the newtypes the library's own APIs use. The
 * conversions to a `Result` (`Try(...).toResult`) are extensions in [[org.llm4s.syntax]].
 */
package object types {

  /** Standard synchronous result type used throughout the library */
  type Result[+A] = Either[error.LLMError, A]

  /** Async result type for Future-based operations */
  type AsyncResult[+A] = Future[Result[A]]

  // Context management newtypes. Like the identifiers in `ProviderModelTypes`, each is an opaque type:
  // no wrapper at runtime, built through its companion and read through an `as...` accessor.

  /** Identifies a semantic block of a conversation. */
  opaque type SemanticBlockId = String

  /** Companion for the [[SemanticBlockId]] opaque type. */
  object SemanticBlockId {

    /** Wraps a raw string as a [[SemanticBlockId]]. */
    def apply(value: String): SemanticBlockId = value

    /** A new random id. */
    def generate(): SemanticBlockId = java.util.UUID.randomUUID().toString.take(8)

    /** Unwraps a [[SemanticBlockId]] to its underlying string. */
    extension (id: SemanticBlockId) def asString: String = id
  }

  /** The key of an entry in an artifact store. */
  opaque type ArtifactKey = String

  /** Companion for the [[ArtifactKey]] opaque type. */
  object ArtifactKey {

    /** Wraps a raw string as an [[ArtifactKey]]. */
    def apply(value: String): ArtifactKey = value

    /** A new random key. */
    def generate(): ArtifactKey = java.util.UUID.randomUUID().toString

    /** The key of `content`: `content_` followed by its SHA-256, so equal content has an equal key. */
    def fromContent(content: String): ArtifactKey = {
      val hash =
        org.llm4s.util.Hex.of(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes("UTF-8")))
      s"content_$hash"
    }

    /** Unwraps an [[ArtifactKey]] to its underlying string. */
    extension (key: ArtifactKey) def asString: String = key
  }

  /** A content size in bytes. */
  opaque type ContentSize = Long

  /** Companion for the [[ContentSize]] opaque type. */
  object ContentSize {

    /** Wraps a byte count as a [[ContentSize]]. */
    def apply(bytes: Long): ContentSize = bytes

    /** The size of `content` encoded as UTF-8. */
    def fromString(content: String): ContentSize = content.getBytes("UTF-8").length.toLong

    /** The size of a byte array. */
    def fromBytes(bytes: Array[Byte]): ContentSize = bytes.length.toLong

    extension (size: ContentSize) {

      /** The size in bytes. */
      def asBytes: Long = size

      /** The size in kibibytes. */
      def toKB: Double = size / 1024.0

      /** The size in mebibytes. */
      def toMB: Double = size / (1024.0 * 1024.0)

      /** Whether the size is over `thresholdBytes`. */
      def exceedsThreshold(thresholdBytes: Long): Boolean = size > thresholdBytes
    }
  }

  /**
   * The share of a token budget held back as a safety margin: a ratio in `[0.0, 1.0)`. Built only through
   * [[HeadroomPercent.create]] or one of the presets, so every value is valid.
   */
  opaque type HeadroomPercent = Double

  /** Companion for the [[HeadroomPercent]] opaque type. */
  object HeadroomPercent {

    /** A headroom of `value` (a ratio: `0.1` is 10%), or a `ValidationError` when it is outside `[0.0, 1.0)`. */
    def create(value: Double): Result[HeadroomPercent] =
      value match {
        case v if v >= 0.0 && v < 1.0 => Right(v)
        case v => Left(error.ValidationError(s"Invalid headroom: $v. Must be between 0.0 and 1.0", "headroom"))
      }

    /** No headroom: the whole budget is usable. */
    val Zero: HeadroomPercent = 0.0

    /** 5% headroom. */
    val Light: HeadroomPercent = 0.05

    /** 8% headroom. */
    val Standard: HeadroomPercent = 0.08

    /** 15% headroom. */
    val Conservative: HeadroomPercent = 0.15

    /** The headroom as a ratio in `[0.0, 1.0)`. */
    extension (headroom: HeadroomPercent) def asRatio: Double = headroom
  }

  /**
   * Helpers for building and combining `Result` values (`Either[LLMError, A]`).
   *
   * {{{
   * val all: Result[Seq[Int]] = Result.traverse(lines)(parseLine)
   * }}}
   */
  object Result {

    /** Wraps `value` as a successful result (`Right(value)`). */
    def success[A](value: A): Result[A] = Right(value)

    /** Wraps `error` as a failed result (`Left(error)`). */
    def failure[A](error: org.llm4s.error.LLMError): Result[A] = Left(error)

    /**
     * `Right` with the option's value, or `Left(error)` for `None`.
     *
     * @param error by-name: evaluated only when `opt` is `None`
     */
    def fromOption[A](opt: Option[A], error: => org.llm4s.error.LLMError): Result[A] =
      opt.toRight(error)

    /**
     * Collects results into a result of their values: the values in order, or the first `Left`.
     */
    def sequence[A](results: Seq[Result[A]]): Result[Seq[A]] =
      traverse(results)(result => result)

    /**
     * Applies `f` to each element in order and collects the values, stopping at the first `Left`:
     * `f` is not called on any element after the first failure, so side effects and expensive work
     * in `f` stop there too.
     */
    def traverse[A, B](items: Seq[A])(f: A => Result[B]): Result[Seq[B]] = {
      val remaining = items.iterator
      @scala.annotation.tailrec
      def loop(acc: Vector[B]): Result[Seq[B]] =
        if (!remaining.hasNext) Right(acc)
        else
          f(remaining.next()) match {
            case Right(value) => loop(acc :+ value)
            case Left(error)  => Left(error)
          }
      loop(Vector.empty)
    }

    /**
     * Runs `operation` and returns its value as a `Right`. A non-fatal exception it throws becomes a `Left`,
     * mapped by [[org.llm4s.error.Safety.fromThrowable Safety.fromThrowable]]: a `CancelledError`, a `NetworkError`
     * (a connection failure or timeout) or, for anything else, an `UnknownError`.
     */
    def safely[A](operation: => A): Result[A] = org.llm4s.error.Safety.safely(operation)

    /**
     * Runs `validator` on every item and collects every error, unlike `traverse`, which stops at the
     * first: `Left` with all the errors in item order when any item fails, otherwise `Right` with the
     * validated values in order.
     */
    def validateAll[A](items: Seq[A])(validator: A => Result[A]): Either[Seq[error.LLMError], Seq[A]] = {
      val results   = items.map(validator)
      val errors    = results.collect { case Left(error) => error }
      val successes = results.collect { case Right(value) => value }
      if (errors.nonEmpty) Left(errors) else Right(successes)
    }
  }
}
