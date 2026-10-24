package org.llm4s.http

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.net.URI
import java.nio.file.Path
import java.util.Locale
import scala.util.Try

/**
 * An outbound HTTP proxy every llm4s HTTP client routes through: the `llm4s.http.proxy` block.
 *
 * {{{
 * llm4s.http.proxy {
 *   url           = "http://proxy.corp:3128"
 *   username      = ${?LLM4S_PROXY_USER}
 *   password      = ${?LLM4S_PROXY_PASSWORD}
 *   nonProxyHosts = ["localhost", "*.internal"]
 * }
 * }}}
 *
 * The proxy is an HTTP proxy: plain-`http` requests go to it in absolute form, `https` ones through a
 * `CONNECT` tunnel. Proxy authentication is HTTP Basic; PAC files and Kerberos/NTLM are not supported.
 *
 * `nonProxyHosts` lists the hosts reached directly. An entry is a host name or address matched exactly
 * (`localhost`), a domain suffix (`*.internal` or `.internal`, which match `internal` itself too), a
 * prefix (`10.*`), or `*` for every host; matching ignores case. A CIDR range is not supported. The
 * default is the loopback addresses ([[HttpProxy.DefaultNonProxyHosts]]); setting the list replaces it.
 *
 * The password is redacted in `toString`. Build one with [[HttpProxy.apply]] or [[HttpProxy.fromUrl]] and
 * adjust it with the `with*` setters; the constructor is private, so a new field never breaks a caller.
 *
 * @param host          the proxy's host name or address
 * @param port          the proxy's port
 * @param username      the user name for proxy authentication, if the proxy requires it
 * @param password      the password for proxy authentication; never printed
 * @param nonProxyHosts the hosts reached directly, not through the proxy
 */
@Stable
final case class HttpProxy private (
  host: String,
  port: Int,
  username: Option[String],
  password: Option[String],
  nonProxyHosts: Seq[String]
):

  def withHost(host: String): HttpProxy                        = copy(host = host)
  def withPort(port: Int): HttpProxy                           = copy(port = port)
  def withUsername(username: String): HttpProxy                = copy(username = Some(username))
  def withUsername(username: Option[String]): HttpProxy        = copy(username = username)
  def withPassword(password: String): HttpProxy                = copy(password = Some(password))
  def withPassword(password: Option[String]): HttpProxy        = copy(password = password)
  def withNonProxyHosts(nonProxyHosts: Seq[String]): HttpProxy = copy(nonProxyHosts = nonProxyHosts)

  /** The user name and password, when both are set: the proxy's Basic credentials. */
  def credentials: Option[(String, String)] =
    for
      user <- username.filter(_.nonEmpty)
      pass <- password
    yield (user, pass)

  /** `true` when `targetHost` is reached directly: it matches an entry of [[nonProxyHosts]]. */
  def bypasses(targetHost: String): Boolean =
    val target = HttpProxy.normalizeHost(targetHost)
    nonProxyHosts.iterator.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).exists { entry =>
      if entry == "*" then true
      else if entry.startsWith("*.") then target.endsWith(entry.drop(1)) || target == entry.drop(2)
      else if entry.startsWith(".") then target.endsWith(entry) || target == entry.drop(1)
      else if entry.endsWith("*") then target.startsWith(entry.dropRight(1))
      else target == HttpProxy.normalizeHost(entry)
    }

  override def toString: String =
    s"HttpProxy($host:$port, username=${username.getOrElse("None")}, password=${password.fold("None")(_ => "***")}, " +
      s"nonProxyHosts=${nonProxyHosts.mkString("[", ", ", "]")})"

object HttpProxy:

  /** The hosts reached directly when `nonProxyHosts` is not set: the loopback addresses, as the JVM's own default. */
  val DefaultNonProxyHosts: Seq[String] = Seq("localhost", "127.*", "::1")

  /**
   * A proxy at `host:port`, without credentials, bypassed for [[DefaultNonProxyHosts]].
   *
   * @param host the proxy's host name or address
   * @param port the proxy's port
   */
  def apply(host: String, port: Int): HttpProxy =
    new HttpProxy(host, port, None, None, DefaultNonProxyHosts)

  /**
   * Parses a proxy URL such as `http://proxy.corp:3128`, the form `HTTPS_PROXY` holds.
   *
   * The scheme must be `http` (a bare `host:port` is read as one); the port is required. A
   * `user:password@` in the URL becomes the credentials. The error never repeats the URL, which may carry
   * a password.
   *
   * @param url the proxy URL
   */
  def fromUrl(url: String): Result[HttpProxy] =
    val trimmed = url.trim
    val text    = if trimmed.contains("://") then trimmed else s"http://$trimmed"
    def invalid(why: String): ConfigurationError =
      ConfigurationError(s"proxy url ${org.llm4s.util.Redaction.url(text)} is not a usable proxy: $why", List("url"))
    Try(new URI(text)).toOption.filter(u => u.getHost != null) match
      case None => Left(invalid("expected http://host:port"))
      case Some(uri) if !uri.getScheme.equalsIgnoreCase("http") =>
        Left(invalid(s"the scheme must be http (an HTTP proxy); ${uri.getScheme} is not supported"))
      case Some(uri) if uri.getPort <= 0 => Left(invalid("the port is missing"))
      case Some(uri) =>
        val userInfo = Option(uri.getUserInfo).filter(_.nonEmpty)
        val user     = userInfo.map(_.takeWhile(_ != ':')).filter(_.nonEmpty)
        val pass     = userInfo.filter(_.contains(':')).map(_.dropWhile(_ != ':').drop(1))
        Right(new HttpProxy(uri.getHost, uri.getPort, user, pass, DefaultNonProxyHosts))

  /** Lower case, without the brackets of an IPv6 literal. */
  private def normalizeHost(host: String): String =
    host.trim.toLowerCase(Locale.ROOT).stripPrefix("[").stripSuffix("]")

/**
 * The TLS trust and client-certificate material every llm4s HTTP client uses: the `llm4s.http.tls` block.
 *
 * {{{
 * llm4s.http.tls {
 *   trustStore         = "/etc/pki/corp-ca.p12"
 *   trustStorePassword = ${?LLM4S_TRUSTSTORE_PASSWORD}
 *   keyStore           = "/etc/pki/client.p12"     # optional: a client certificate, for mTLS gateways
 *   keyStorePassword   = ${?LLM4S_KEYSTORE_PASSWORD}
 * }
 * }}}
 *
 * The trust store's certificates are trusted '''in addition to''' the JDK's default ones, so a corporate
 * CA that re-signs traffic is accepted without losing the public CAs. A store may be PKCS12 or JKS; the
 * type is detected from the file. The passwords are redacted in `toString`.
 *
 * Build one with [[HttpTls.default]] and the `with*` setters; the constructor is private, so a new field
 * never breaks a caller.
 *
 * @param trustStore         a store of extra CA certificates to trust, if any
 * @param trustStorePassword the trust store's password, if it has one; never printed
 * @param keyStore           a store holding the client certificate and its key, for mutual TLS, if any
 * @param keyStorePassword   the key store's password, also used for the key; never printed
 */
@Stable
final case class HttpTls private (
  trustStore: Option[Path],
  trustStorePassword: Option[String],
  keyStore: Option[Path],
  keyStorePassword: Option[String]
):

  def withTrustStore(trustStore: Path): HttpTls                 = copy(trustStore = Some(trustStore))
  def withTrustStore(trustStore: Option[Path]): HttpTls         = copy(trustStore = trustStore)
  def withTrustStorePassword(password: String): HttpTls         = copy(trustStorePassword = Some(password))
  def withTrustStorePassword(password: Option[String]): HttpTls = copy(trustStorePassword = password)
  def withKeyStore(keyStore: Path): HttpTls                     = copy(keyStore = Some(keyStore))
  def withKeyStore(keyStore: Option[Path]): HttpTls             = copy(keyStore = keyStore)
  def withKeyStorePassword(password: String): HttpTls           = copy(keyStorePassword = Some(password))
  def withKeyStorePassword(password: Option[String]): HttpTls   = copy(keyStorePassword = password)

  /** `true` when neither store is set, so every client keeps the JDK's TLS defaults. */
  def isDefault: Boolean = trustStore.isEmpty && keyStore.isEmpty

  override def toString: String =
    s"HttpTls(trustStore=${trustStore.getOrElse("None")}, trustStorePassword=${trustStorePassword.fold("None")(_ => "***")}, " +
      s"keyStore=${keyStore.getOrElse("None")}, keyStorePassword=${keyStorePassword.fold("None")(_ => "***")})"

object HttpTls:

  /** No store: the JDK's default trust and no client certificate. */
  val default: HttpTls = new HttpTls(None, None, None, None)

  /**
   * Creates an [[HttpTls]].
   *
   * @param trustStore         a store of extra CA certificates to trust
   * @param trustStorePassword the trust store's password
   * @param keyStore           a store holding the client certificate and key
   * @param keyStorePassword   the key store's password
   */
  def apply(
    trustStore: Option[Path] = None,
    trustStorePassword: Option[String] = None,
    keyStore: Option[Path] = None,
    keyStorePassword: Option[String] = None
  ): HttpTls =
    new HttpTls(trustStore, trustStorePassword, keyStore, keyStorePassword)

/**
 * How llm4s's HTTP clients reach the network: an optional [[HttpProxy]] and the [[HttpTls]] material, read
 * once from the `llm4s.http` block (`Llm4sConfig.httpNetwork()`) and honoured by every provider client - the
 * JDK client in core, the `openai-java` and `anthropic-java` SDKs and the AWS SDK.
 *
 * A named provider section can override it for one route with its own `http` block
 * (`llm4s.providers.<name>.http`), whose keys take precedence over `llm4s.http` one by one.
 *
 * [[HttpNetworkSettings.default]] - no proxy, no extra TLS material - leaves every client as it was before
 * the setting existed: the JDK client keeps the JVM's default proxy selector.
 *
 * Build one with [[HttpNetworkSettings.apply]] or [[HttpNetworkSettings.default]] and the `with*` setters;
 * the constructor is private, so a new field never breaks a caller.
 *
 * @param proxy the proxy to route through, or `None` for a direct connection
 * @param tls   the extra trust and client-certificate material
 */
@Stable
final case class HttpNetworkSettings private (
  proxy: Option[HttpProxy],
  tls: HttpTls
):

  def withProxy(proxy: HttpProxy): HttpNetworkSettings         = copy(proxy = Some(proxy))
  def withProxy(proxy: Option[HttpProxy]): HttpNetworkSettings = copy(proxy = proxy)
  def withTls(tls: HttpTls): HttpNetworkSettings               = copy(tls = tls)

  /** `true` when there is no proxy and no TLS material: every client keeps its own defaults. */
  def isDefault: Boolean = proxy.isEmpty && tls.isDefault

  /** The proxy to use for a request to `host`, or `None` when there is none or `host` bypasses it. */
  def proxyFor(host: String): Option[HttpProxy] = proxy.filterNot(_.bypasses(host))

object HttpNetworkSettings:

  /** No proxy and no TLS material: every client keeps its own defaults. */
  val default: HttpNetworkSettings = new HttpNetworkSettings(None, HttpTls.default)

  /**
   * Creates an [[HttpNetworkSettings]].
   *
   * @param proxy the proxy to route through, or `None` for a direct connection
   * @param tls   the extra trust and client-certificate material
   */
  def apply(proxy: Option[HttpProxy] = None, tls: HttpTls = HttpTls.default): HttpNetworkSettings =
    new HttpNetworkSettings(proxy, tls)
