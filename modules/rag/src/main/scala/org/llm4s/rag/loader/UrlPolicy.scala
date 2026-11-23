package org.llm4s.rag.loader

import org.llm4s.annotation.Experimental
import org.llm4s.core.safety.NetworkSecurity
import org.llm4s.error.NetworkError

import java.net.{ InetAddress, URI }
import java.util.Locale
import scala.util.Try

/**
 * Which URLs `UrlLoader`, `WebCrawlerLoader` and the crawler's robots.txt fetch may request, and at which addresses.
 *
 * A loader asks the policy before every request it makes: the seed URL, each crawled link, every redirect hop and
 * `robots.txt`. The policy answers with the addresses the host may be reached at, and the loader connects to one of
 * those addresses itself (sending the original `Host` header and, for `https`, the original server name and
 * certificate check), so a host whose DNS answer changes after the check (DNS rebinding) cannot redirect the
 * connection elsewhere.
 *
 * [[UrlPolicy.default]] is the SSRF check of `NetworkSecurity.validateUrl`: `http` and `https` only, and no host that
 * resolves to a loopback, private, link-local or other non-public address. Its `with*` setters add an allow-list of
 * hosts, extra blocked hosts or private-network access. An application with other rules implements this trait.
 */
@Experimental
trait UrlPolicy {

  /**
   * The addresses `url` may be fetched from, or why it may not be fetched.
   *
   * @return a non-empty list of addresses, tried in order; the loader never connects to any other
   */
  def resolve(url: URI): Either[NetworkError, Seq[InetAddress]]
}

object UrlPolicy {

  /**
   * The built-in policy.
   *
   * @param allowedHosts when non-empty, only these hosts and their subdomains may be fetched (case-insensitive)
   * @param blockedHosts hosts that, with their subdomains, are refused in addition to the defaults
   * @param allowPrivateNetworks whether a host may resolve to a loopback, private or other non-public address. The
   *                             cloud metadata endpoint (`169.254.169.254`, `fd00:ec2::254`, `metadata.google.internal`,
   *                             `metadata.internal`, and `169.254.169.254` inside a NAT64, IPv4-mapped or 6to4
   *                             IPv6 address) stays refused either way.
   * @param allowedSchemes the URL schemes that may be fetched; the loaders speak only `http` and `https`
   * @param resolver how a host name is resolved: `InetAddress.getAllByName` by default
   */
  final case class Standard private (
    allowedHosts: Set[String],
    blockedHosts: Set[String],
    allowPrivateNetworks: Boolean,
    allowedSchemes: Set[String],
    resolver: String => Either[NetworkError, Seq[InetAddress]]
  ) extends UrlPolicy {

    /** Only `hosts` and their subdomains may be fetched; an empty set lifts the restriction. */
    def withAllowedHosts(hosts: Set[String]): Standard = copy(allowedHosts = hosts.map(normalizeHost))

    /** Refuse `hosts` and their subdomains too. */
    def withBlockedHosts(hosts: Set[String]): Standard = copy(blockedHosts = hosts.map(normalizeHost))

    /** Whether a host may resolve to a non-public address, such as an intranet's. */
    def withAllowPrivateNetworks(allow: Boolean): Standard = copy(allowPrivateNetworks = allow)

    /** The URL schemes that may be fetched (`http`, `https`). */
    def withAllowedSchemes(schemes: Set[String]): Standard = copy(allowedSchemes = schemes.map(normalize))

    /** Resolve host names with `resolve` instead of the system resolver. */
    def withResolver(resolve: String => Either[NetworkError, Seq[InetAddress]]): Standard = copy(resolver = resolve)

    def resolve(url: URI): Either[NetworkError, Seq[InetAddress]] =
      for {
        scheme <- Right(Option(url.getScheme).map(normalize).getOrElse(""))
        _ <- check(
          allowedSchemes.contains(scheme),
          s"Protocol '$scheme' is not allowed. Allowed: ${allowedSchemes.toSeq.sorted.mkString(", ")}"
        )
        host <- Option(url.getHost)
          .map(h => normalizeHost(h.stripPrefix("[").stripSuffix("]")))
          .filter(_.nonEmpty) match {
          case Some(h) => Right(h)
          case None    => blocked("URL has no host")
        }
        _ <- check(
          allowedHosts.isEmpty || allowedHosts.exists(matches(host, _)),
          s"Hostname '$host' is not in the allowed hosts"
        )
        _         <- check(!hostBlocked(host), s"Hostname '$host' is blocked")
        addresses <- resolver(host)
        _         <- check(addresses.nonEmpty, s"DNS resolution returned no address for '$host'")
        _ <- addresses.find(addressBlocked) match {
          case Some(ip) => blocked(s"Resolved IP '${ip.getHostAddress}' for host '$host' is in a blocked range")
          case None     => Right(())
        }
      } yield addresses

    private def hostBlocked(host: String): Boolean =
      blockedHosts.exists(matches(host, _)) ||
        (if (allowPrivateNetworks) MetadataHostnames.exists(matches(host, _))
         else NetworkSecurity.isBlockedHostname(host))

    private def addressBlocked(ip: InetAddress): Boolean =
      if (allowPrivateNetworks) isMetadataAddress(ip) else NetworkSecurity.isBlockedIP(ip)

    override def toString: String =
      s"UrlPolicy.Standard(allowedHosts=$allowedHosts, blockedHosts=$blockedHosts, " +
        s"allowPrivateNetworks=$allowPrivateNetworks, allowedSchemes=$allowedSchemes)"
  }

  object Standard {

    /** The default policy's rules: see [[UrlPolicy.default]]. */
    def apply(
      allowedHosts: Set[String] = Set.empty,
      blockedHosts: Set[String] = Set.empty,
      allowPrivateNetworks: Boolean = false,
      allowedSchemes: Set[String] = Set("http", "https"),
      resolver: String => Either[NetworkError, Seq[InetAddress]] = systemResolver
    ): Standard =
      new Standard(
        allowedHosts.map(normalizeHost),
        blockedHosts.map(normalizeHost),
        allowPrivateNetworks,
        allowedSchemes.map(normalize),
        resolver
      )
  }

  /** Resolves `host` with `InetAddress.getAllByName`, which cannot be interrupted (see `NetworkSecurity`). */
  val systemResolver: String => Either[NetworkError, Seq[InetAddress]] = host =>
    Try(InetAddress.getAllByName(host).toSeq).toEither.left
      .map(e => NetworkError(s"DNS resolution failed for '$host': ${e.getMessage}", Some(e), "ssrf-protection"))

  /**
   * Today's SSRF check (`NetworkSecurity.validateUrl`): `http` and `https`, public addresses only, `localhost` and
   * the cloud metadata host names refused.
   */
  val default: Standard = Standard()

  private val MetadataHostnames: Set[String] = Set("metadata.google.internal", "metadata.internal")

  private val MetadataAddresses: Set[InetAddress] =
    Set(NetworkSecurity.CloudMetadataIP, "fd00:ec2::254").map(InetAddress.getByName)

  /**
   * A cloud metadata address, also when an IPv6 address carries it: IPv4-mapped `::ffff:0:0/96` and NAT64
   * `64:ff9b::/96` (RFC 6052) in their last 4 bytes, 6to4 `2002::/16` (RFC 3056) in bytes 2-5. A gateway that
   * translates such an address reaches the metadata endpoint itself.
   */
  private def isMetadataAddress(ip: InetAddress): Boolean = {
    val b                                     = ip.getAddress
    def u(i: Int): Int                        = b(i) & 0xff
    def zeros(from: Int, until: Int): Boolean = (from until until).forall(b(_) == 0)
    def v4(from: Int): InetAddress = InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, from, from + 4))
    val embedded =
      if (b.length != 16) None
      else if (zeros(0, 10) && u(10) == 0xff && u(11) == 0xff) Some(v4(12))
      else if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && zeros(4, 12)) Some(v4(12))
      else if (u(0) == 0x20 && u(1) == 0x02) Some(v4(2))
      else None
    MetadataAddresses.contains(ip) || embedded.exists(MetadataAddresses.contains)
  }

  private def normalize(s: String): String = s.trim.toLowerCase(Locale.ROOT)

  /** A host name in canonical form: lower case, without the trailing dot of an absolute name (`example.com.`). */
  private def normalizeHost(s: String): String = normalize(s).stripSuffix(".")

  private def matches(host: String, entry: String): Boolean =
    host == entry || host.endsWith(s".$entry")

  private def blocked(message: String): Either[NetworkError, Nothing] =
    Left(NetworkError(message, None, "ssrf-protection"))

  private def check(ok: Boolean, message: => String): Either[NetworkError, Unit] =
    if (ok) Right(()) else blocked(message)
}
