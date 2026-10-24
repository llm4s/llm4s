package org.llm4s.http

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.io.IOException
import java.net.{ Authenticator, InetSocketAddress, PasswordAuthentication, Proxy, ProxySelector, SocketAddress, URI }
import java.nio.file.{ Files, Path }
import java.security.KeyStore
import java.security.cert.{ CertificateException, X509Certificate }
import java.util.{ List => JList }
import javax.net.ssl.{ KeyManager, KeyManagerFactory, SSLContext, TrustManagerFactory, X509TrustManager }
import scala.util.Try

/**
 * The trust and key material an [[HttpTls]] resolves to, in the forms the HTTP stacks take: an `SSLContext`
 * for the JDK client, its socket factory and trust manager for OkHttp (the vendor SDKs), and the managers
 * for the AWS SDK.
 *
 * @param sslContext   a TLS context initialised with [[trustManager]] and [[keyManagers]]
 * @param trustManager the JDK's default trust plus the configured trust store's certificates
 * @param keyManagers  the client certificate's key managers; empty without a key store
 */
final private[llm4s] case class TlsMaterial(
  sslContext: SSLContext,
  trustManager: X509TrustManager,
  keyManagers: Seq[KeyManager]
)

/**
 * Turns [[HttpNetworkSettings]] into the objects the HTTP stacks take: a `ProxySelector` and `Authenticator`
 * for the JDK client, a `java.net.Proxy` for a client that targets one base URL, and [[TlsMaterial]].
 */
private[llm4s] object HttpNetwork:

  /** A selector that sends every request through `proxy`, except to the hosts it bypasses. */
  def proxySelector(proxy: HttpProxy): ProxySelector =
    new ProxySelector:
      private val route = javaProxy(proxy)

      override def select(uri: URI): JList[Proxy] =
        val host = Option(uri).flatMap(u => Option(u.getHost)).getOrElse("")
        JList.of(if proxy.bypasses(host) then Proxy.NO_PROXY else route)

      override def connectFailed(uri: URI, sa: SocketAddress, ioe: IOException): Unit = ()

  /** The proxy as a `java.net.Proxy`, its address left unresolved so no lookup happens until a connection. */
  def javaProxy(proxy: HttpProxy): Proxy =
    new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port))

  /**
   * The `java.net.Proxy` for a client that only ever calls `baseUrl`: the proxy, unless `baseUrl`'s host
   * bypasses it. `None` means a direct connection.
   */
  def proxyForUrl(settings: HttpNetworkSettings, baseUrl: String): Option[Proxy] =
    val host = Try(URI.create(baseUrl.trim)).toOption.flatMap(u => Option(u.getHost)).getOrElse("")
    settings.proxyFor(host).map(javaProxy)

  /**
   * An authenticator answering only the proxy's own challenge, with its Basic credentials; `None` when the
   * proxy has none. A server's challenge gets no answer, so the proxy's credentials never reach a provider.
   */
  def authenticator(proxy: HttpProxy): Option[Authenticator] =
    proxy.credentials.map { case (user, password) =>
      new Authenticator:
        override def getPasswordAuthentication: PasswordAuthentication =
          if getRequestorType == Authenticator.RequestorType.PROXY && getRequestingHost.equalsIgnoreCase(proxy.host)
          then new PasswordAuthentication(user, password.toCharArray)
          else null
    }

  /**
   * The TLS material for `tls`, or `None` when it sets no store, in which case each client keeps the JDK's
   * defaults. A store that cannot be read - missing, corrupt, or with a wrong password - is a
   * `ConfigurationError` naming the key (`tls.trustStore`, `tls.keyStore`) and the file, never the password.
   */
  def tlsMaterial(tls: HttpTls): Result[Option[TlsMaterial]] =
    if tls.isDefault then Right(None)
    else
      for
        extraTrust <- tls.trustStore.fold[Result[Option[X509TrustManager]]](Right(None))(path =>
          load("trustStore", path, tls.trustStorePassword)
            .flatMap(trustManagerOf("trustStore", path.toString, _))
            .map(Some(_))
        )
        defaultTrust <- trustManagerOf("trustStore", "the JDK default trust store", null)
        keyManagers <- tls.keyStore.fold[Result[Seq[KeyManager]]](Right(Seq.empty))(path =>
          load("keyStore", path, tls.keyStorePassword).flatMap(keyManagersOf(path, _, tls.keyStorePassword))
        )
        trust = extraTrust.fold(defaultTrust)(extra => CompositeTrustManager(Seq(defaultTrust, extra)))
        context <- Try {
          val ctx = SSLContext.getInstance("TLS")
          ctx.init(if keyManagers.isEmpty then null else keyManagers.toArray, Array(trust), null)
          ctx
        }.toEither.left.map(e => ConfigurationError(s"tls: cannot initialise TLS: ${e.getMessage}", List("tls")))
      yield Some(TlsMaterial(context, trust, keyManagers))

  private def load(key: String, path: Path, password: Option[String]): Result[KeyStore] =
    if !Files.isRegularFile(path) then
      Left(ConfigurationError(s"tls.$key: $path does not exist or is not a file", List(s"tls.$key")))
    else
      Try(KeyStore.getInstance(path.toFile, password.map(_.toCharArray).orNull)).toEither.left.map { e =>
        // The JDK's messages ("keystore password was incorrect") never carry the password itself.
        val cause = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        ConfigurationError(s"tls.$key: cannot read $path: $cause", List(s"tls.$key"))
      }

  private def trustManagerOf(key: String, source: String, store: KeyStore): Result[X509TrustManager] =
    Try {
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      factory.init(store)
      factory.getTrustManagers.collectFirst { case tm: X509TrustManager => tm }
    }.toOption.flatten
      .toRight(ConfigurationError(s"tls.$key: $source holds no usable trust material", List(s"tls.$key")))

  private def keyManagersOf(path: Path, store: KeyStore, password: Option[String]): Result[Seq[KeyManager]] =
    Try {
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      factory.init(store, password.map(_.toCharArray).orNull)
      factory.getKeyManagers.toSeq
    }.toEither.left.map { e =>
      val cause = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
      ConfigurationError(s"tls.keyStore: cannot read the key in $path: $cause", List("tls.keyStore"))
    }

  /**
   * Trusts a chain any of `managers` trusts: the JDK's defaults first, then the configured store, so the
   * extra CAs add to the public ones rather than replacing them.
   */
  final private case class CompositeTrustManager(managers: Seq[X509TrustManager]) extends X509TrustManager:

    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
      firstTrusting(_.checkClientTrusted(chain, authType))

    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
      firstTrusting(_.checkServerTrusted(chain, authType))

    override def getAcceptedIssuers: Array[X509Certificate] = managers.flatMap(_.getAcceptedIssuers).toArray

    // The trust manager contract is exceptions: the last manager's refusal is the one thrown.
    private def firstTrusting(check: X509TrustManager => Unit): Unit =
      val failures = managers.iterator.map(m => Try(check(m)).failed.toOption)
      val refusals = failures.takeWhile(_.isDefined).flatten.toVector
      if refusals.size == managers.size then
        throw refusals.lastOption.getOrElse(new CertificateException("no trust manager"))
