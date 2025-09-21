package org.llm4s.types.typeclass

import cats.Monad
import org.llm4s.Result
import org.llm4s.types.Result

/**
 * Monad type class for monadic operations.
 */
trait LLMMonad[F[_]] extends LLMFunctor[F] {
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
}

object LLMMonad {
  def apply[F[_]](implicit ev: Monad[F]): Monad[F] = ev

  // Extension methods
  implicit class MonadOps[F[_]: Monad, A](fa: F[A]) {
    def flatMap[B](f: A => F[B]): F[B] = Monad[F].flatMap(fa)(f)
    def >>=[B](f: A => F[B]): F[B]     = flatMap(f) // Haskell-style operator
  }

  // Result instance
  implicit val resultMonad: LLMMonad[Result] = new LLMMonad[Result] {
    def pure[A](a: A): Result[A]                                   = Result.success(a)
    def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] = fa.flatMap(f)
    def map[A, B](fa: Result[A])(f: A => B): Result[B]             = fa.map(f)
  }
}
