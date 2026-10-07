package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental

/**
 * A named provider section's `auth` block: the workload's identity token, plus the keys the
 * provider declares in `ProviderConfigSpec.authExtras` (a token URL, a federation rule id, ...),
 * resolved by validation. Values are redacted in `toString`.
 *
 * Extras are trimmed and a blank one is dropped, however the config is built - as a section's
 * `auth` block is read - so a blank `clientId` is absent rather than posted empty, and a required key
 * left blank is reported as missing.
 */
@Experimental
final case class AuthConfig private (identityToken: IdentitySource, extras: Map[String, String]):
  def extra(key: String): Option[String] = extras.get(key)

  def withIdentityToken(identityToken: IdentitySource): AuthConfig = copy(identityToken = identityToken)
  def withExtras(extras: Map[String, String]): AuthConfig          = copy(extras = AuthConfig.nonBlank(extras))

  override def toString: String =
    s"AuthConfig($identityToken, ${extras.keys.toSeq.sorted.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"

object AuthConfig:
  val IdentityTokenFileKey: String = "identityTokenFile"
  val IdentityTokenKey: String     = "identityToken"
  val ReservedKeys: Set[String]    = Set(IdentityTokenFileKey, IdentityTokenKey)

  /** Creates an [[AuthConfig]]. Named arguments are the supported way to construct one. */
  def apply(identityToken: IdentitySource, extras: Map[String, String] = Map.empty): AuthConfig =
    new AuthConfig(identityToken, nonBlank(extras))

  private def nonBlank(extras: Map[String, String]): Map[String, String] =
    extras.collect { case (key, value) if value.trim.nonEmpty => key -> value.trim }
