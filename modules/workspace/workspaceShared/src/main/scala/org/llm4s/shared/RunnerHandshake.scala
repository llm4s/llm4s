package org.llm4s.shared

import java.nio.charset.StandardCharsets
import java.security.{ MessageDigest, SecureRandom }
import java.util.Base64

/**
 * How a client proves to the workspace runner that it started the runner's container (#3046).
 *
 * The client makes a random token per container and passes it in the container's environment
 * ([[TokenEnvVar]]); every WebSocket handshake carries it as `Authorization: Bearer <token>`, which the runner
 * compares in constant time. A handshake with an `Origin` header is refused whatever it carries: only a browser sends
 * one, and a web page the developer visits must never reach the runner through `ws://localhost`.
 */
private[llm4s] object RunnerHandshake {

  /** The container's environment variable holding the token. The runner keeps it out of every command's environment. */
  val TokenEnvVar: String = "LLM4S_RUNNER_TOKEN"

  /**
   * The container's environment variable holding how long, in whole seconds, the runner waits with no client
   * connected before it exits (#3053). `0` never exits.
   */
  val IdleTimeoutEnvVar: String = "LLM4S_RUNNER_IDLE_TIMEOUT_SECONDS"

  /** The handshake header carrying the token. */
  val AuthorizationHeader: String = "Authorization"

  /** The shortest token the runner accepts: a token it could guess its way to is no token. */
  val MinTokenLength: Int = 32

  private val random = new SecureRandom()

  /** A new token: 32 random bytes, base64url without padding (43 characters). */
  def newToken(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /** The `Authorization` header's value for `token`. */
  def authorization(token: String): String = s"Bearer $token"

  /** The runner's token, from its environment's value, or why the runner must not start with it. */
  def tokenFrom(value: Option[String]): Either[String, String] =
    value.map(_.trim).filter(_.nonEmpty) match {
      case None =>
        Left(
          s"$TokenEnvVar is not set: the runner refuses to start without a token (#3046). Set it to a random value of " +
            s"at least $MinTokenLength characters and send it as '$AuthorizationHeader: Bearer <token>'"
        )
      case Some(t) if t.length < MinTokenLength =>
        Left(s"$TokenEnvVar is ${t.length} characters long; it must be at least $MinTokenLength")
      case Some(t) if t.exists(c => c.isWhitespace || c.isControl) =>
        Left(s"$TokenEnvVar holds whitespace or a control character")
      case Some(t) => Right(t)
    }

  /**
   * Whether a WebSocket handshake with `headers` may open: `Right` when it carries no `Origin` header and exactly one
   * `Authorization: Bearer <expected>`, else why not. Header names are matched case-insensitively.
   */
  def check(expected: String, headers: Map[String, Seq[String]]): Either[String, Unit] = {
    def values(name: String): Seq[String] =
      headers.toSeq.collect { case (k, vs) if k.equalsIgnoreCase(name) => vs }.flatten
    if (values("Origin").nonEmpty) Left("a handshake with an Origin header (a browser's) is refused")
    else
      values(AuthorizationHeader) match {
        case Seq(only) if only.startsWith("Bearer ") && sameToken(only.stripPrefix("Bearer "), expected) => Right(())
        case Seq() => Left("no token")
        case _     => Left("wrong token")
      }
  }

  /**
   * Compares two tokens in constant time: their SHA-256 digests, so neither the position of the first difference nor
   * the length of the expected token shows in the time taken.
   */
  private def sameToken(presented: String, expected: String): Boolean = {
    def digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))
    MessageDigest.isEqual(digest(presented), digest(expected))
  }
}
