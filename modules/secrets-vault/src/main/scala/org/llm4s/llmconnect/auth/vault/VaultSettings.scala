package org.llm4s.llmconnect.auth.vault

import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.URI
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Where [[VaultApiKeyProvider]] reads an API key in HashiCorp Vault (#2479): the field `field` of the secret at
 * `path` in the key/value secrets engine mounted at `mount`.
 *
 * {{{
 * // the field apiKey of secret/data/llm4s/openai (KV version 2, mounted at secret/)
 * val settings = VaultSettings("https://vault.internal:8200", Secret(vaultToken), "llm4s/openai", "apiKey")
 *   .withRefreshInterval(1.minute)
 * }}}
 *
 * @param address         Vault's base URL, `http(s)://host:port[/prefix]`, without `/v1`; no user info, query or
 *                        fragment. `http` is refused unless the host is loopback or `allowCleartext` is set
 * @param token           the Vault token sent as `X-Vault-Token`; never printed by `toString` nor put in an error
 * @param path            the secret's path inside the mount, such as `llm4s/openai`
 * @param field           the key of the secret's data that holds the API key
 * @param mount           the path the KV secrets engine is mounted at; `secret` by default, as in `vault server -dev`
 * @param kvVersion       the KV engine's version: `V2` (versioned, the default) or `V1`
 * @param namespace       the Vault Enterprise / HCP Vault namespace, sent as `X-Vault-Namespace`
 * @param refreshInterval how long a key read from Vault is used before it is read again
 * @param requestTimeout  how long one read from Vault may take
 * @param allowCleartext  accepts an `http://` address whose host is not loopback. Off by default: with it on, the
 *                        Vault token and the secret in the reply cross the network unencrypted, readable and
 *                        alterable by anything on the path. Loopback (`localhost`, 127.0.0.0/8, `::1`) needs no opt-in
 */
@Experimental
final case class VaultSettings private (
  address: String,
  token: Secret,
  path: String,
  field: String,
  mount: String,
  kvVersion: VaultSettings.KvVersion,
  namespace: Option[String],
  refreshInterval: FiniteDuration,
  requestTimeout: FiniteDuration,
  allowCleartext: Boolean
):

  /** The KV secrets engine's mount path. */
  def withMount(mount: String): VaultSettings = copy(mount = mount)

  /** The KV secrets engine's version. */
  def withKvVersion(kvVersion: VaultSettings.KvVersion): VaultSettings = copy(kvVersion = kvVersion)

  /** The Vault Enterprise / HCP Vault namespace. */
  def withNamespace(namespace: String): VaultSettings = copy(namespace = Some(namespace))

  /** The Vault Enterprise / HCP Vault namespace, or none. */
  def withNamespace(namespace: Option[String]): VaultSettings = copy(namespace = namespace)

  /** How long a key read from Vault is used before it is read again. */
  def withRefreshInterval(refreshInterval: FiniteDuration): VaultSettings = copy(refreshInterval = refreshInterval)

  /** How long one read from Vault may take. */
  def withRequestTimeout(requestTimeout: FiniteDuration): VaultSettings = copy(requestTimeout = requestTimeout)

  /**
   * Accepts, or again refuses, an `http://` address whose host is not loopback. With it on the Vault token and the
   * secret in the reply travel unencrypted, so use it only on a network you trust end to end.
   */
  def withCleartext(allowed: Boolean): VaultSettings = copy(allowCleartext = allowed)

  /** The secret's location, as `mount/path#field`; holds nothing secret. */
  def location: String = s"$mount/$path#$field"

  override def toString: String =
    s"VaultSettings(${Redaction.url(Option(address).getOrElse(""))}, token=***, $location, $kvVersion, " +
      s"namespace=${namespace.getOrElse("-")}, refreshInterval=$refreshInterval, requestTimeout=$requestTimeout" +
      (if allowCleartext then ", cleartext=allowed)" else ")")

object VaultSettings:

  /** The KV secrets engine's version, which decides the URL of a read and where the data sits in the reply. */
  enum KvVersion:
    /** Unversioned: `GET /v1/<mount>/<path>`, the data in `data`. */
    case V1

    /** Versioned: `GET /v1/<mount>/data/<path>`, the latest version's data in `data.data`. */
    case V2

  /** `secret`, the mount `vault server -dev` creates. */
  val DefaultMount: String = "secret"

  /** Five minutes: a rotated key is in use at most this long after Vault has it. */
  val DefaultRefreshInterval: FiniteDuration = 5.minutes

  /** Ten seconds. */
  val DefaultRequestTimeout: FiniteDuration = 10.seconds

  /** The field `field` of the KV v2 secret at `path` under `secret/`, read again every five minutes. */
  def apply(address: String, token: Secret, path: String, field: String): VaultSettings =
    new VaultSettings(
      address,
      token,
      path,
      field,
      DefaultMount,
      KvVersion.V2,
      None,
      DefaultRefreshInterval,
      DefaultRequestTimeout,
      false
    )

  /**
   * Whether `host`, as `URI.getHost` returns it, is a loopback literal: `localhost` (any case), an IPv4 address in
   * 127.0.0.0/8 or the IPv6 `::1`. Decided from the text alone, with no DNS lookup, so a name that merely resolves to
   * a loopback address is not one.
   */
  private[vault] def isLoopbackHost(host: String): Boolean =
    val h = host.stripPrefix("[").stripSuffix("]").toLowerCase(java.util.Locale.ROOT)
    h == "localhost" || h == "::1" || (h match
      case Ipv4(a, b, c, d) => a == "127" && Seq(b, c, d).forall(_.toInt <= 255)
      case _                => false
    )

  private val Ipv4 = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""".r

  /**
   * `settings` with blank parts refused and the mount and path stripped of surrounding slashes. A failure is a
   * `ConfigurationError` that names the bad setting and never holds the token.
   */
  private[vault] def validated(settings: VaultSettings): Result[VaultSettings] =
    def trimSlashes(s: String): String = Option(s).getOrElse("").trim.stripPrefix("/").stripSuffix("/")
    val mount                          = trimSlashes(settings.mount)
    val path                           = trimSlashes(settings.path)
    val address                        = Option(settings.address).getOrElse("").trim.stripSuffix("/")
    val uri                            = Try(new URI(address)).toOption
    val token                          = Option(settings.token).map(_.reveal.trim).getOrElse("")
    def invalid(what: String)          = Left(ConfigurationError(s"Vault settings: $what"))
    val shown                          = Redaction.url(address)
    if !uri.exists(u => Set("http", "https").contains(Option(u.getScheme).getOrElse("")) && u.getHost != null) then
      invalid(s"address '$shown' is not an http(s) URL")
    else if uri.exists(_.getRawUserInfo != null) then invalid(s"address '$shown' must not hold user info")
    else if uri.exists(u => u.getRawQuery != null || u.getRawFragment != null) then
      invalid(s"address '$shown' must not hold a query or a fragment")
    else if uri.exists(u => u.getScheme == "http" && !isLoopbackHost(u.getHost)) && !settings.allowCleartext then
      invalid(
        s"address '$shown' is http to a host that is not loopback, so the token and the secret would travel " +
          "unencrypted: use https, or withCleartext(true) on a network you trust"
      )
    else if token.isEmpty then invalid("token is blank")
    else if token.exists(c => c == '\n' || c == '\r') then invalid("token holds more than one line")
    else if mount.isEmpty then invalid("mount is blank")
    else if path.isEmpty then invalid("path is blank")
    else if Option(settings.field).forall(_.trim.isEmpty) then invalid("field is blank")
    else if settings.namespace.exists(_.trim.isEmpty) then invalid("namespace is blank")
    else if settings.refreshInterval <= Duration.Zero then
      invalid(s"refreshInterval must be positive, got ${settings.refreshInterval}")
    else if settings.requestTimeout <= Duration.Zero then
      invalid(s"requestTimeout must be positive, got ${settings.requestTimeout}")
    else
      Right(
        settings.copy(
          address = address,
          token = Secret(token),
          mount = mount,
          path = path,
          field = settings.field.trim,
          namespace = settings.namespace.map(_.trim)
        )
      )
