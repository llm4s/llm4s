package org.llm4s.types.typeclass

import org.llm4s.types.Result

/**
 * Validate type class for consistent validation patterns.
 */
trait Validate[A] {
  def validate(a: A): Result[A]
}

object Validate {
  def apply[A](implicit ev: Validate[A]): Validate[A] = ev

  def validate[A: Validate](a: A): Result[A] = Validate[A].validate(a)

  // Smart constructor
  def fromFunction[A](f: A => Result[A]): Validate[A] = (a: A) => f(a)

  // Extension methods
  implicit class ValidateOps[A: Validate](a: A) {
    def validate: Result[A] = Validate[A].validate(a)
  }
}
