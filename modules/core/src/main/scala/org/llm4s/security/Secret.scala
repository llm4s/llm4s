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

@Stable
object Secret {

  /** A configured credential: an API key, a password, a client secret. Never evicted from the registry. */
  def apply(value: String): Secret = {
    val v = orEmpty(value)
    KnownSecrets.register(v, KnownSecrets.Kind.Configured)
    new Secret(v)
  }

  /** A short-lived token obtained at runtime (OAuth, IAM, token exchange). May be evicted at the registry's cap. */
  def token(value: String): Secret = {
    val v = orEmpty(value)
    KnownSecrets.register(v, KnownSecrets.Kind.Token)
    new Secret(v)
  }

  /** HTTP Basic credentials: registers the password and the header's base64 of `user:password`. */
  def basic(user: String, password: String): Secret = {
    KnownSecrets.register(orEmpty(password), KnownSecrets.Kind.Configured)
    Secret(Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8)))
  }

  private def orEmpty(value: String): String = if (value == null) "" else value
}
