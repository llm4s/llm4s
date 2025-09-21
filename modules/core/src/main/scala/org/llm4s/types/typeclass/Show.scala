package org.llm4s.types.typeclass

import org.llm4s.error.LLMError

/**
 * Show type class for human-readable string representation.
 * This enables consistent formatting across all domain types.
 */
trait Show[A] {
  def show(a: A): String
}

object Show {
  def apply[A](implicit ev: Show[A]): Show[A] = ev

  def show[A: Show](a: A): String = Show[A].show(a)

  // Smart constructor
  def fromFunction[A](f: A => String): Show[A] = (a: A) => f(a)

  // Extension method syntax
  implicit class ShowOps[A: Show](a: A) {
    def show: String = Show[A].show(a)
  }

  // Common instances
  implicit val stringShow: Show[String]     = fromFunction(identity)
  implicit val intShow: Show[Int]           = fromFunction(_.toString)
  implicit val llmErrorShow: Show[LLMError] = fromFunction(_.formatted)
}
