package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental

/**
 * A named provider section's `auth` block: the workload's identity token, plus the keys the
 * provider declares in `ProviderConfigSpec.authExtras` (a token URL, a federation rule id, ...),
 * resolved by validation. Values are redacted in `toString`.
 */
@Experimental
final case class AuthConfig(identityToken: IdentitySource, extras: Map[String, String]):
  def extra(key: String): Option[String] = extras.get(key)
  override def toString: String =
    s"AuthConfig($identityToken, ${extras.keys.toSeq.sorted.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"

object AuthConfig:
  val IdentityTokenFileKey: String = "identityTokenFile"
  val IdentityTokenKey: String     = "identityToken"
  val ReservedKeys: Set[String]    = Set(IdentityTokenFileKey, IdentityTokenKey)
