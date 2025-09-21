package org.llm4s.syntax

import org.llm4s.error
import org.llm4s.types.Result

object syntax {
  implicit class ResultOps[A](private val result: Result[A]) extends AnyVal {
    def getOrElse[B >: A](default: => B): B = result.getOrElse(default)

    def orElse[B >: A](alternative: => Result[B]): Result[B] =
      result.fold(_ => alternative, Right(_))

    def mapError(f: error.LLMError => error.LLMError): Result[A] =
      result.left.map(f)

    def recover[B >: A](pf: PartialFunction[error.LLMError, B]): Result[B] =
      result.fold(error => pf.lift(error).map(Right(_)).getOrElse(Left(error)), Right(_))

    def recoverWith[B >: A](pf: PartialFunction[error.LLMError, Result[B]]): Result[B] =
      result.fold(error => pf.lift(error).getOrElse(Left(error)), Right(_))

    def tap(effect: A => Unit): Result[A] = {
      result.foreach(effect)
      result
    }

    def tapError(effect: error.LLMError => Unit): Result[A] = {
      result.left.foreach(effect)
      result
    }
  }
}
