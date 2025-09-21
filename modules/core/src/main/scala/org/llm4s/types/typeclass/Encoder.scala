package org.llm4s.types.typeclass

import ujson.{ Value => JsonValue }

/**
 * Encoder type class for JSON serialization.
 */
trait Encoder[A] {
  def encode(a: A): JsonValue
}

object Encoder {
  def apply[A](implicit ev: Encoder[A]): Encoder[A] = ev

  def encode[A: Encoder](a: A): JsonValue = Encoder[A].encode(a)

  // Smart constructor
  def fromFunction[A](f: A => JsonValue): Encoder[A] = (a: A) => f(a)

  // Extension methods
  implicit class EncoderOps[A: Encoder](a: A) {
    def encode: JsonValue = Encoder[A].encode(a)
  }
}
