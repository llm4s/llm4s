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

  /**
   * Runs `use`, a request sent with `token` and the handling of its reply, keeping `token` registered until it
   * returns or throws. Two generations cover a source that refreshes now and then; a source that hands out a fresh
   * token per call rotates past a request still in flight, whose error body must still be scrubbed of its token.
   */
  def holding[A](token: String)(use: => A): A = {
    KnownSecrets.register(token, KnownSecrets.Kind.Token)
    scala.util.Using.resource(new AutoCloseable { def close(): Unit = KnownSecrets.forget(token) })(_ => use)
  }
}
