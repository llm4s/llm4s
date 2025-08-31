package org.llm4s.types.typeclass

import cats.{ Functor => CatsFunctor }
import org.llm4s.types.Result

/**
 * Higher-Kinded Type class for functors.
 * Enables mapping over wrapped values.
 */
trait LLMFunctor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

object Functor {
  def apply[F[_]](implicit ev: CatsFunctor[F]): CatsFunctor[F] = ev

  // Extension methods
  implicit class FunctorOps[F[_]: CatsFunctor, A](fa: F[A]) {
    def map[B](f: A => B): F[B] = CatsFunctor[F].map(fa)(f)
  }

  // Result instance
  implicit val resultFunctor: CatsFunctor[Result] = new CatsFunctor[Result] {
    def map[A, B](fa: Result[A])(f: A => B): Result[B] = fa.map(f)
  }
}
