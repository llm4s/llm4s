package org.llm4s.security

import org.llm4s.annotation.Stable
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A credential. Its `toString` is `***`, and its value is read only through [[reveal]]. Creating one
 * registers the value with llm4s, which then removes it from every error message, log line, exchange log
 * and trace it would otherwise appear in. Values under 8 characters are not registered (they would mask
 * ordinary words).
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
    KnownSecrets.register(value, KnownSecrets.Kind.Configured)
    new Secret(value)
  }

  /** A short-lived token obtained at runtime (OAuth, IAM, token exchange). May be evicted at the registry's cap. */
  def token(value: String): Secret = {
    KnownSecrets.register(value, KnownSecrets.Kind.Token)
    new Secret(value)
  }

  /** HTTP Basic credentials: registers the password and the header's base64 of `user:password`. */
  def basic(user: String, password: String): Secret = {
    KnownSecrets.register(password, KnownSecrets.Kind.Configured)
    Secret(Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8)))
  }
}
