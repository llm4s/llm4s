package org.llm4s.security

import org.llm4s.annotation.Stable
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A credential. Its `toString` is `***`, and its value is read only through [[reveal]]. Creating one
 * registers the value with llm4s, which then removes it, by exact match, from the error messages, exchange
 * logs and other diagnostic text llm4s builds (traces follow in a later release). Values under 8 characters
 * are not registered (they would mask ordinary words). A `null` value is read as `""`: never registered,
 * and revealed as `""`.
 *
 * The registry is process-wide and there is no public way to remove a value from it; the companion says which
 * factory keeps a value for the life of the process and which lets it go.
 */
@Stable
final class Secret private (private val value: String) {
  def reveal: String            = value
  override def toString: String = "***"
  override def equals(o: Any): Boolean = o match {
    case s: Secret => s.value == value
    case _         => false
  }
  override def hashCode: Int = value.hashCode
}

/**
 * The factories of [[Secret]], and the contract of the registry behind them.
 *
 * Creating a `Secret` registers its value process-wide, for exact-match scrubbing of the diagnostic text llm4s
 * builds. Which factory creates it decides how long the value stays registered:
 *
 *  - [[Secret.apply]] is a configured credential. It is never evicted: it stays registered for the life of
 *    the process, whether or not the `Secret` is still referenced. So is the password of [[Secret.basic]].
 *  - [[Secret.token]] is a short-lived token. Once the registry holds more than 4096 values, the tokens
 *    registered longest ago are evicted to bring it back to that number.
 *  - A value under 8 characters is not registered by either.
 *  - There is no public way to forget a value.
 *
 * The consequence: `Secret(...)` is for a fixed set of long-lived credentials - the keys an application is
 * configured with. A key created per tenant, per user or per request must be a `Secret.token(...)`. Created with
 * `Secret(...)`, each distinct one is held, and matched against every scrubbed text, until the process exits: the
 * registry grows without bound, and llm4s logs one warning when it passes 4096 values.
 */
@Stable
object Secret {

  /**
   * A configured credential: an API key, a password, a client secret. Never evicted from the registry, so use it
   * for a fixed set of long-lived credentials only; a per-tenant or per-request key is a [[token]].
   */
  def apply(value: String): Secret = {
    val v = orEmpty(value)
    KnownSecrets.register(v, KnownSecrets.Kind.Configured)
    new Secret(v)
  }

  /**
   * A short-lived credential obtained or chosen at runtime: an OAuth, IAM or exchanged token, and equally a
   * per-tenant or per-request key. May be evicted once the registry holds more than 4096 values, the least
   * recently registered first; after that it is no longer scrubbed from diagnostic text.
   */
  def token(value: String): Secret = {
    val v = orEmpty(value)
    KnownSecrets.register(v, KnownSecrets.Kind.Token)
    new Secret(v)
  }

  /** HTTP Basic credentials: registers the password and the header's base64 of `user:password`. */
  def basic(user: String, password: String): Secret = {
    val pw = orEmpty(password)
    KnownSecrets.register(pw, KnownSecrets.Kind.Configured)
    Secret(Base64.getEncoder.encodeToString(s"${orEmpty(user)}:$pw".getBytes(StandardCharsets.UTF_8)))
  }

  private def orEmpty(value: String): String = if (value == null) "" else value
}
