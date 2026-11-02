package org.llm4s.security

/**
 * Registers each token a source obtains, and keeps the token it replaced registered too: an error body
 * for a request made with the old token may still be on its way. The generation before that is forgotten.
 */
final private[llm4s] class TokenGenerations {
  private var held = Vector.empty[String]

  /**
   * Serialised, so that a concurrent `rotate` of the same token cannot see it as current, skip registration and
   * send it before the first caller has registered it. The section does no I/O: one registry update at most.
   */
  def rotate(next: String): Unit = synchronized {
    if (!held.lastOption.contains(next)) {
      KnownSecrets.register(next, KnownSecrets.Kind.Token)
      (held :+ next).dropRight(2).foreach(KnownSecrets.forget)
      held = (held :+ next).takeRight(2)
    }
  }
}
