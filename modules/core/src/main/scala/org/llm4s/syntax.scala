package org.llm4s

import org.llm4s.annotation.Stable
import org.llm4s.error.{ LLMError, Safety }
import org.llm4s.types.Result

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
 * The extension methods of llm4s, all in one object and none in scope until imported:
 *
 * {{{
 * import org.llm4s.syntax.*          // all of them
 * import org.llm4s.syntax.toResult   // or one, by name
 *
 * Try("42".toInt).toResult                         // Result[Int]
 * sys.props.get("user.home").toResult(ConfigurationError("no home"))
 * new IllegalStateException("boom").toLLMError     // LLMError
 * result.recover { case _: RateLimitError => "fallback" }.tap(println)
 * }}}
 *
 * `import org.llm4s.types.*` brings in none of them, so it does not add `toResult` to every `Try`, `Option` and
 * `Future` in the file.
 */
@Stable
object syntax {

  extension [A](t: Try[A]) {

    /** The `Try`'s value as a `Right`, or its exception as a `Left`, mapped by [[Safety.fromThrowable]]. */
    def toResult: Result[A] = Safety.fromTry(t)
  }

  extension [A](option: Option[A]) {

    /**
     * `Right` with the option's value, or `Left(ifEmpty)` for `None`.
     *
     * @param ifEmpty by-name: evaluated only when the option is `None`
     */
    def toResult(ifEmpty: => LLMError): Result[A] = option.toRight(ifEmpty)
  }

  extension [A](future: Future[A]) {

    /**
     * The future's value as a `Right`, or its failure as a `Left`, mapped by [[Safety.fromThrowable]]: the returned
     * future never fails because the original did. This is the one `Future` to `AsyncResult` conversion.
     */
    def toResult(using ExecutionContext): Future[Result[A]] =
      future.map(Right(_)).recover { case t => Left(Safety.fromThrowable(t)) }
  }

  extension (t: Throwable) {

    /** This exception as an [[LLMError]]: [[Safety.fromThrowable]]. */
    def toLLMError: LLMError = Safety.fromThrowable(t)
  }

  /**
   * Combinators on a `Result[A]` (`Either[LLMError, A]`) that `Either` does not have. They parallel `Try`'s, typed
   * for `LLMError` on the left.
   */
  extension [A](result: Result[A]) {

    /**
     * Transforms the error value without changing the success type.
     * Leaves `Right` values untouched.
     *
     * @param f transforms the `LLMError`; must return an `LLMError`
     */
    def mapError(f: LLMError => LLMError): Result[A] =
      result.left.map(f)

    /**
     * Converts a matching `Left` into a `Right` using the supplied partial function.
     *
     * If the partial function is not defined for the error, the original `Left`
     * is returned unchanged.  `Right` values pass through without invoking `pf`.
     *
     * @param pf partial function from `LLMError` to a recovery value
     */
    def recover[B >: A](pf: PartialFunction[LLMError, B]): Result[B] =
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
    def recoverWith[B >: A](pf: PartialFunction[LLMError, Result[B]]): Result[B] =
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
    def tapError(effect: LLMError => Unit): Result[A] = {
      result.left.foreach(effect)
      result
    }
  }
}
