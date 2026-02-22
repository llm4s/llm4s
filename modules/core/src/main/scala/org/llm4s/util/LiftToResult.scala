package org.llm4s.util

import org.llm4s.error.ThrowableOps._
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * Evidence that values in effect `F` can be lifted into `Result`.
 *
 *  The canonical instance is for `cats.Id`, which wraps any value in `Right`.
 *  Future backends with richer effects (e.g. `cats.effect.IO`, `scala.concurrent.Future`)
 *  supply their own instance to bridge into the synchronous `Result` error channel.
 *
 *  @tparam F the source effect
 */
trait LiftToResult[F[_]] {

  /** Evaluate `fa` and lift the outcome into [[Result]], wrapping a successful value in `Right`. */
  def apply[A](fa: F[A]): Result[A]
}

object LiftToResult {

  /** `cats.Id` always succeeds — wraps the value in `Right`. */
  implicit val idInstance: LiftToResult[cats.Id] = new LiftToResult[cats.Id] {
    def apply[A](fa: cats.Id[A]): Result[A] = Right(fa)
  }

  /**
   * Type alias for `Either[Throwable, *]`, provided so that [[LiftToResult]] can be
   * instantiated for this 1-parameter shape without requiring kind-projector.
   */
  type ThrowableEither[A] = Either[Throwable, A]

  /**
   * Lifts `Either[Throwable, A]` into `Result[A]` by converting any `Left` throwable
   * into an [[org.llm4s.error.LLMError]] using
   * [[org.llm4s.error.ThrowableOps.RichThrowable#toLLMError]] with the
   * [[org.llm4s.core.safety.DefaultErrorMapper]].
   *
   *  This instance is useful when integrating with Java or Try-based code that surfaces
   *  failures as `Throwable` rather than a typed error.
   */
  implicit val eitherInstance: LiftToResult[ThrowableEither] = new LiftToResult[ThrowableEither] {
    def apply[A](fa: ThrowableEither[A]): Result[A] = fa.left.map(_.toLLMError)
  }

  /**
   * Lifts `scala.util.Try[A]` into `Result[A]`: `Success(a)` becomes `Right(a)`;
   * `Failure(t)` converts the throwable to an [[org.llm4s.error.LLMError]] using
   * [[org.llm4s.error.ThrowableOps.RichThrowable#toLLMError]] with the
   * [[org.llm4s.core.safety.DefaultErrorMapper]].
   */
  implicit val tryInstance: LiftToResult[Try] = new LiftToResult[Try] {
    def apply[A](fa: Try[A]): Result[A] = fa match {
      case Success(a) => Right(a)
      case Failure(t) => Left(t.toLLMError)
    }
  }

  /**
   * Creates a [[LiftToResult]] instance for [[Option]]:
   * `Some(a)` lifts to `Right(a)`; `None` lifts to `Left(onNone)`.
   *
   *  There is no implicit instance for `Option` because there is no canonical error
   *  for the empty case — callers must supply the error explicitly.
   *
   *  @param onNone the error returned when the option is empty
   */
  def forOption(onNone: => org.llm4s.error.LLMError): LiftToResult[Option] =
    new LiftToResult[Option] {
      def apply[A](fa: Option[A]): Result[A] = fa.toRight(onNone)
    }
}
