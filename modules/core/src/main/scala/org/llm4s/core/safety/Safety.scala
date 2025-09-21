package org.llm4s.core.safety

import cats.data.ValidatedNec
import cats.syntax.either._
import cats.syntax.apply._
import org.llm4s.error.LLMError
import org.llm4s.types.Result

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
 * Pure helpers for safe, typed error handling.
 * All helpers return Either-based results or cats ValidatedNec for aggregation.
 */
object Safety {

  /**
   * Pure helpers
   */

  def safely[A](thunk: => A)(implicit em: ErrorMapper = DefaultErrorMapper): Result[A] =
    fromTry(Try(thunk))

  def fromTry[A](t: Try[A])(implicit em: ErrorMapper = DefaultErrorMapper): Result[A] = t match {
    case Success(v) => Right(v)
    case Failure(e) => Left(em(e))
  }

  def fromOption[A](oa: Option[A], ifEmpty: => LLMError): Result[A] =
    oa.toRight(ifEmpty)

  def mapError[A](r: Result[A])(f: LLMError => LLMError): Result[A] =
    r.leftMap(f)

  /** Sequence results and accumulate all errors using ValidatedNec. */
  def sequenceV[A](xs: List[Result[A]]): ValidatedNec[LLMError, List[A]] =
    xs.foldRight(cats.data.Validated.validNec[LLMError, List[A]](Nil)) { (r, acc) =>
      (cats.data.Validated.fromEither(r).toValidatedNec, acc).mapN(_ :: _)
    }

  /**
   * Minimal Future helpers
   */

  object future {
    def fromFuture[A](
      fa: Future[A]
    )(implicit ec: ExecutionContext, em: ErrorMapper = DefaultErrorMapper): Future[Result[A]] =
      fa.map(Right(_)).recover { case t => Left(em(t)) }

    // Do not wrap in Try: Future.apply already captures synchronous exceptions.
    def safely[A](thunk: => A)(implicit ec: ExecutionContext, em: ErrorMapper = DefaultErrorMapper): Future[Result[A]] =
      Future(thunk).map(Right(_)).recover { case t => Left(em(t)) }
  }
}
