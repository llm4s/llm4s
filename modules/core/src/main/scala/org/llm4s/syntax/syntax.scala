package org.llm4s.syntax

import org.llm4s.error
import org.llm4s.types.Result

/**
 * Extension methods for `Result[A]` (`Either[LLMError, A]`).
 *
 * Import `org.llm4s.syntax.syntax._` to bring these into scope.
 *
 * @example
 * {{{
 * import org.llm4s.syntax.syntax._
 *
 * val result: Result[String] = Right("hello")
 * result
 *   .recover { case _: RateLimitError => "fallback" }
 *   .tap(println)
 * }}}
 */
object syntax {

  /**
   * Enriches `Result[A]` with combinators for error handling and side effects.
   *
   * These methods parallel standard `Either` and `Try` combinators but are
   * typed specifically for `LLMError` on the left side, making the failure
   * domain explicit at each call site.
   */
  implicit class ResultOps[A](private val result: Result[A]) extends AnyVal {

    /** Returns the success value, or `default` when the result is a `Left`. */
    def getOrElse[B >: A](default: => B): B = result.getOrElse(default)

    /**
     * Returns this result when it is a `Right`, or evaluates `alternative`
     * when it is a `Left`.  The original error is discarded.
     */
    def orElse[B >: A](alternative: => Result[B]): Result[B] =
      result.fold(_ => alternative, Right(_))

    /**
     * Transforms the error value without changing the success type.
     * Leaves `Right` values untouched.
     *
     * @param f transforms the `LLMError`; must return an `LLMError`
     */
    def mapError(f: error.LLMError => error.LLMError): Result[A] =
      result.left.map(f)

    /**
     * Converts a matching `Left` into a `Right` using the supplied partial function.
     *
     * If the partial function is not defined for the error, the original `Left`
     * is returned unchanged.  `Right` values pass through without invoking `pf`.
     *
     * @param pf partial function from `LLMError` to a recovery value
     */
    def recover[B >: A](pf: PartialFunction[error.LLMError, B]): Result[B] =
      result.fold(error => pf.lift(error).map(Right(_)).getOrElse(Left(error)), Right(_))

    /**
     * Converts a matching `Left` into a new `Result` using the supplied partial function.
     *
     * Useful when the recovery operation can itself fail.  If the partial
     * function is not defined for the error, the original `Left` is returned
     * unchanged.  `Right` values pass through without invoking `pf`.
     *
     * @param pf partial function from `LLMError` to a new `Result[B]`
     */
    def recoverWith[B >: A](pf: PartialFunction[error.LLMError, Result[B]]): Result[B] =
      result.fold(error => pf.lift(error).getOrElse(Left(error)), Right(_))

    /**
     * Executes `effect` for its side effect when the result is a `Right`,
     * then returns the original result unchanged.
     * Useful for logging or metrics without breaking a `for`-comprehension chain.
     *
     * @param effect side-effecting function invoked on success; its return value is discarded
     */
    def tap(effect: A => Unit): Result[A] = {
      result.foreach(effect)
      result
    }

    /**
     * Executes `effect` for its side effect when the result is a `Left`,
     * then returns the original result unchanged.
     * Useful for logging errors without altering the failure path.
     *
     * @param effect side-effecting function invoked on failure; its return value is discarded
     */
    def tapError(effect: error.LLMError => Unit): Result[A] = {
      result.left.foreach(effect)
      result
    }
  }
}
