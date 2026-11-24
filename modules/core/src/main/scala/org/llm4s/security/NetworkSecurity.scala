package org.llm4s.security

import org.llm4s.annotation.Stable
import org.llm4s.error.{ LLMError, NetworkError, ValidationError }
import org.llm4s.types.Result

import java.net.{ InetAddress, URI }
import java.util.Locale
import scala.util.Try

/**
 * Network security utilities for SSRF protection.
 *
 * Provides IP address validation to prevent Server-Side Request Forgery (SSRF) attacks
 * by blocking requests to internal networks, cloud metadata endpoints, and other
 * potentially sensitive destinations.
 *
 * == Protected IP Ranges ==
 * Checked against the IANA IPv4 and IPv6 Special-Purpose Address Registries: every entry that is not globally
 * reachable is blocked.
 *  - Private networks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (RFC 1918); IPv6 unique-local fc00::/7 (RFC 4193)
 *    and the deprecated site-local fec0::/10
 *  - Loopback: 127.0.0.0/8, ::1
 *  - Unspecified / "this network": 0.0.0.0/8, ::
 *  - Link-local: 169.254.0.0/16, fe80::/10
 *  - Cloud metadata: 169.254.169.254 (AWS, GCP, Azure)
 *  - Multicast: 224.0.0.0/4, ff00::/8
 *  - Reserved 240.0.0.0/4, including the limited broadcast address 255.255.255.255
 *  - IETF protocol assignments 192.0.0.0/24 and the deprecated 6to4 relay anycast 192.88.99.0/24
 *  - Carrier-grade NAT 100.64.0.0/10, benchmarking 198.18.0.0/15 and 2001:2::/48
 *  - Documentation/test ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24, 2001:db8::/32, 3fff::/20
 *  - Every IPv6 address outside global unicast 2000::/3 (RFC 4291 2.4), which IANA has never allocated for routing:
 *    among others the IPv4-compatible ::/96, the SIIT IPv4-translated ::ffff:0:0:0/96, discard-only 100::/64, the
 *    dummy prefix 100:0:0:1::/64, local-use NAT64 64:ff9b:1::/48 and SRv6 5f00::/16
 *  - The IETF protocol assignments block 2001::/23 (Teredo 2001::/32, benchmarking, the deprecated ORCHID and the
 *    unassigned rest), except its globally reachable entries: 2001:1::1, 2001:1::2 and 2001:1::3, AMT 2001:3::/32,
 *    AS112 2001:4:112::/48, ORCHIDv2 2001:20::/28 and drone remote ID 2001:30::/28
 *  - An IPv6 address that embeds an IPv4 address - IPv4-mapped ::ffff:0:0/96, NAT64 64:ff9b::/96 and 6to4
 *    2002::/16 - is blocked when the embedded IPv4 address is
 *
 * The globally reachable entries of the IPv4 registry (192.31.196.0/24, 192.52.193.0/24, 192.175.48.0/24) are allowed.
 *
 * == Name resolution ==
 * `validateUrl` and `validateHostname` resolve the host with `InetAddress.getAllByName`, which cannot be
 * interrupted: a caller that runs them under a deadline (as the HTTP tool does) can stop waiting, but the thread doing
 * the lookup stays blocked until the system resolver answers or gives up, by its own timeout.
 *
 * Both return the addresses they vetted. A caller that connects to one of those addresses (sending the original
 * `Host` header and, for `https`, the original server name) is safe from a host whose DNS answer changes after the
 * check (DNS rebinding); a caller that hands the URL to a client that resolves the name again is not.
 *
 * == Errors ==
 * A refusal - a blocked host name or address, a protocol that is not allowed, a URL that cannot be parsed - is a
 * [[org.llm4s.error.ValidationError]]: asking again gives the same answer, so it is not retryable. A name that
 * cannot be resolved is a [[org.llm4s.error.NetworkError]], which is.
 *
 * @example
 * {{{
 * import org.llm4s.security.NetworkSecurity
 *
 * // Validate a URL before fetching
 * NetworkSecurity.validateUrl("https://example.com/api") // Right(the addresses example.com resolves to)
 * NetworkSecurity.validateUrl("http://169.254.169.254/") // Left(ValidationError)
 * NetworkSecurity.validateUrl("http://192.168.1.1/admin") // Left(ValidationError)
 * }}}
 */
@Stable
object NetworkSecurity {

  /**
   * Default blocked hostnames (in addition to IP-based blocking).
   */
  private val DefaultBlockedHostnames: Set[String] = Set(
    "localhost",
    "localhost.localdomain",
    "metadata.google.internal", // GCP metadata
    "metadata.internal"         // Azure metadata
  )

  /**
   * Cloud metadata IP address (used by AWS, GCP, Azure).
   */
  val CloudMetadataIP: String = "169.254.169.254"

  /**
   * Check if an IP address is in a private/internal range that should be blocked.
   *
   * @param ip The IP address to check
   * @return true if the IP is in a blocked range
   */
  def isBlockedIP(ip: InetAddress): Boolean = {
    val address = ip.getAddress

    // The JDK predicates cover scoped and platform-specific forms; the byte checks below cover the ranges they miss.
    ip.isLoopbackAddress ||
    ip.isLinkLocalAddress ||
    ip.isSiteLocalAddress ||
    ip.isMulticastAddress ||
    ip.isAnyLocalAddress ||
    isCloudMetadata(ip) ||
    (address.length == 4 && isBlockedIPv4(address)) ||
    (address.length == 16 && isBlockedIPv6(address))
  }

  /**
   * Check if an IP is the cloud metadata endpoint.
   */
  private def isCloudMetadata(ip: InetAddress): Boolean =
    ip.getHostAddress == CloudMetadataIP

  /** Unsigned value of byte `i`. */
  private def u(address: Array[Byte], i: Int): Int = address(i) & 0xff

  /** Whether bytes `from` (inclusive) to `until` (exclusive) of `address` are all zero. */
  private def zeros(address: Array[Byte], from: Int, until: Int): Boolean =
    (from until until).forall(address(_) == 0)

  /** Whether `address` starts with `prefix` (whole bytes). */
  private def startsWith(address: Array[Byte], prefix: Int*): Boolean =
    prefix.indices.forall(i => u(address, i) == prefix(i))

  /**
   * Every blocked IPv4 range, from the 4 address bytes alone, so that the same rule applies to an IPv4 address
   * embedded in an IPv6 one.
   */
  private def isBlockedIPv4(address: Array[Byte]): Boolean = {
    val b0 = u(address, 0)
    val b1 = u(address, 1)
    val b2 = u(address, 2)
    b0 == 0 ||                              // 0.0.0.0/8, "this network" (RFC 1122)
    b0 >= 240 ||                            // 240.0.0.0/4 reserved (RFC 1112), with 255.255.255.255 broadcast (RFC 919)
    b0 == 10 ||                             // 10.0.0.0/8 (RFC 1918)
    b0 == 127 ||                            // 127.0.0.0/8 loopback
    (b0 == 169 && b1 == 254) ||             // 169.254.0.0/16 link-local, cloud metadata
    (b0 == 172 && b1 >= 16 && b1 <= 31) ||  // 172.16.0.0/12 (RFC 1918)
    (b0 == 192 && b1 == 168) ||             // 192.168.0.0/16 (RFC 1918)
    (b0 >= 224 && b0 <= 239) ||             // 224.0.0.0/4 multicast
    (b0 == 100 && b1 >= 64 && b1 <= 127) || // 100.64.0.0/10 carrier-grade NAT (RFC 6598)
    (b0 == 198 && (b1 == 18 || b1 == 19)) || // 198.18.0.0/15 benchmarking (RFC 2544)
    (b0 == 192 && b1 == 0 && b2 == 0) ||     // 192.0.0.0/24 IETF protocol assignments (RFC 6890)
    (b0 == 192 && b1 == 88 && b2 == 99) ||   // 192.88.99.0/24 deprecated 6to4 relay anycast (RFC 7526)
    (b0 == 192 && b1 == 0 && b2 == 2) ||     // 192.0.2.0/24 TEST-NET-1 (RFC 5737)
    (b0 == 198 && b1 == 51 && b2 == 100) ||  // 198.51.100.0/24 TEST-NET-2
    (b0 == 203 && b1 == 0 && b2 == 113)      // 203.0.113.0/24 TEST-NET-3
  }

  /** The 4 bytes of `address` starting at `from`. */
  private def embeddedIPv4(address: Array[Byte], from: Int): Array[Byte] =
    java.util.Arrays.copyOfRange(address, from, from + 4)

  /**
   * Every blocked IPv6 range, from the 16 address bytes. The JDK's `isSiteLocalAddress` matches only the deprecated
   * `fec0::/10`, not unique-local `fc00::/7`, so this checks the ranges itself.
   *
   * Only global unicast `2000::/3` (RFC 4291 2.4) is routable: IANA has allocated nothing outside it for that, so every
   * other address is blocked - `::/96` (unspecified, loopback, IPv4-compatible), the SIIT IPv4-translated
   * `::ffff:0:0:0/96`, discard-only `100::/64`, the dummy prefix `100:0:0:1::/64`, local-use NAT64 `64:ff9b:1::/48`,
   * SRv6 `5f00::/16`, unique-local `fc00::/7`, link-local and site-local `fe80::/9`, multicast `ff00::/8` and the
   * reserved rest - except the two forms that carry an IPv4 address and are judged by it.
   */
  private def isBlockedIPv6(address: Array[Byte]): Boolean = {
    val b0 = u(address, 0)
    val b1 = u(address, 1)
    // ::ffff:0:0/96 IPv4-mapped (RFC 4291 2.5.5.2): judged by the IPv4 address it carries
    val ipv4Mapped = zeros(address, 0, 10) && u(address, 10) == 0xff && u(address, 11) == 0xff
    // 64:ff9b::/96 NAT64 well-known prefix (RFC 6052): judged by the embedded IPv4 address
    val nat64 = startsWith(address, 0x00, 0x64, 0xff, 0x9b) && zeros(address, 4, 12)
    if (ipv4Mapped || nat64) isBlockedIPv4(embeddedIPv4(address, 12))
    else
      (b0 & 0xe0) != 0x20 || // outside 2000::/3 global unicast
      isBlockedIetfAssignment(address) ||
      // 2001:db8::/32 documentation (RFC 3849)
      startsWith(address, 0x20, 0x01, 0x0d, 0xb8) ||
      // 2002::/16 6to4 (RFC 3056): judged by the IPv4 address in bytes 2-5
      (startsWith(address, 0x20, 0x02) && isBlockedIPv4(embeddedIPv4(address, 2))) ||
      // 3fff::/20 documentation (RFC 9637)
      (b0 == 0x3f && b1 == 0xff && (u(address, 2) & 0xf0) == 0)
  }

  /**
   * Whether `address` is in the IETF protocol assignments block `2001::/23` (RFC 2928) and not one of its globally
   * reachable entries. This blocks Teredo `2001::/32` (RFC 4380, which tunnels to an address the guard cannot see),
   * benchmarking `2001:2::/48` (RFC 5180), the deprecated ORCHID `2001:10::/28` (RFC 4843) and the unassigned rest.
   */
  private def isBlockedIetfAssignment(address: Array[Byte]): Boolean = {
    val b2      = u(address, 2)
    val b3      = u(address, 3)
    val inBlock = u(address, 0) == 0x20 && u(address, 1) == 0x01 && (b2 & 0xfe) == 0
    def globallyReachable: Boolean =
      // 2001:1::1 PCP anycast (RFC 7723), 2001:1::2 TURN anycast (RFC 8155), 2001:1::3 DNS-SD SRP anycast (RFC 9665)
      (b2 == 0x00 && b3 == 0x01 && zeros(address, 4, 15) && u(address, 15) >= 1 && u(address, 15) <= 3) ||
        (b2 == 0x00 && b3 == 0x03) ||                              // 2001:3::/32 AMT (RFC 7450)
        startsWith(address, 0x20, 0x01, 0x00, 0x04, 0x01, 0x12) || // 2001:4:112::/48 AS112-v6 (RFC 7535)
        (b2 == 0x00 && (b3 & 0xf0) == 0x20) ||                     // 2001:20::/28 ORCHIDv2 (RFC 7343)
        (b2 == 0x00 && (b3 & 0xf0) == 0x30)                        // 2001:30::/28 drone remote ID (RFC 9374)
    inBlock && !globallyReachable
  }

  /**
   * Check if a hostname is one of the blocked ones (`localhost` and the cloud metadata names) or a subdomain of one
   * (case-insensitive).
   *
   * @param hostname The hostname to check
   * @return true if the hostname should be blocked
   */
  def isBlockedHostname(hostname: String): Boolean = isBlockedHostname(hostname, Set.empty)

  /**
   * Check if a hostname should be blocked (case-insensitive).
   *
   * @param hostname The hostname to check
   * @param additionalBlocked Hostnames to block, with their subdomains, on top of the built-in ones
   * @return true if the hostname should be blocked
   */
  def isBlockedHostname(hostname: String, additionalBlocked: Set[String]): Boolean = {
    val normalizedHostname = hostname.toLowerCase(Locale.ROOT).trim
    val allBlocked         = DefaultBlockedHostnames ++ additionalBlocked.map(_.toLowerCase(Locale.ROOT))

    allBlocked.exists(blocked => normalizedHostname == blocked || normalizedHostname.endsWith(s".$blocked"))
  }

  /** The URL protocols [[validateUrl(urlString:String)*]] allows. */
  private val DefaultAllowedProtocols: Set[String] = Set("http", "https")

  /**
   * Validate an `http` or `https` URL for SSRF safety, and return the addresses its host may be reached at.
   *
   * This performs DNS resolution and checks that no resolved address is in a blocked range.
   * It also validates the hostname against known blocked hostnames.
   *
   * The lookup (`InetAddress.getAllByName`) cannot be interrupted, so it can block the calling thread for as long as
   * the system resolver takes, whatever deadline the caller has; see "Name resolution" above.
   *
   * @param urlString The URL to validate
   * @return the vetted addresses of the URL's host, never empty; `Left(ValidationError)` if the URL is refused,
   *         `Left(NetworkError)` if its host cannot be resolved
   */
  def validateUrl(urlString: String): Result[Seq[InetAddress]] =
    validateUrl(urlString, Set.empty, DefaultAllowedProtocols)

  /**
   * [[validateUrl(urlString:String)*]] with more hostnames blocked and a chosen set of protocols.
   *
   * @param urlString The URL to validate
   * @param additionalBlockedHostnames Hostnames to block, with their subdomains, on top of the built-in ones
   * @param allowedProtocols Allowed URL protocols, in lower case
   * @return the vetted addresses of the URL's host, never empty; `Left(ValidationError)` if the URL is refused,
   *         `Left(NetworkError)` if its host cannot be resolved
   */
  def validateUrl(
    urlString: String,
    additionalBlockedHostnames: Set[String],
    allowedProtocols: Set[String]
  ): Result[Seq[InetAddress]] =
    for {
      uri <- Try(new URI(urlString)).toEither.left.map(e => refused("url", s"Invalid URL: ${e.getMessage}"))
      url <- Try(uri.toURL).toEither.left.map(e => refused("url", s"Cannot convert to URL: ${e.getMessage}"))
      protocol = Option(url.getProtocol).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      _ <- Either.cond(
        allowedProtocols.contains(protocol),
        (),
        refused("url", s"Protocol '$protocol' is not allowed. Allowed: ${allowedProtocols.toSeq.sorted.mkString(", ")}")
      )
      host = Option(url.getHost).getOrElse("")
      _         <- Either.cond(host.nonEmpty, (), refused("url", "URL has no host"))
      addresses <- vetted("url", host, additionalBlockedHostnames)
    } yield addresses

  /**
   * Validate a hostname for SSRF safety (without full URL parsing), and return the addresses it may be reached at.
   *
   * Like `validateUrl`, this resolves the name with `InetAddress.getAllByName`, which cannot be interrupted: the
   * calling thread waits for the system resolver, whatever deadline the caller has.
   *
   * @param hostname The hostname to validate
   * @return the vetted addresses of the host, never empty; `Left(ValidationError)` if the host is refused,
   *         `Left(NetworkError)` if it cannot be resolved
   */
  def validateHostname(hostname: String): Result[Seq[InetAddress]] = validateHostname(hostname, Set.empty)

  /**
   * [[validateHostname(hostname:String)*]] with more hostnames blocked.
   *
   * @param hostname The hostname to validate
   * @param additionalBlockedHostnames Hostnames to block, with their subdomains, on top of the built-in ones
   */
  def validateHostname(hostname: String, additionalBlockedHostnames: Set[String]): Result[Seq[InetAddress]] =
    for {
      _         <- Either.cond(hostname.nonEmpty, (), refused("hostname", "Hostname is empty"))
      addresses <- vetted("hostname", hostname, additionalBlockedHostnames)
    } yield addresses

  /** The addresses `host` resolves to, when the name and every one of them is allowed. */
  private def vetted(field: String, host: String, additionalBlockedHostnames: Set[String]): Result[Seq[InetAddress]] =
    for {
      _ <- Either.cond(
        !isBlockedHostname(host, additionalBlockedHostnames),
        (),
        refused(field, s"Hostname '$host' is blocked")
      )
      // Not a refusal: the resolver may answer the next time it is asked.
      addresses <- Try(InetAddress.getAllByName(host).toSeq).toEither.left
        .map(e => NetworkError(s"DNS resolution failed for '$host': ${e.getMessage}", Some(e), host))
      _ <- Either.cond(addresses.nonEmpty, (), refused(field, s"DNS resolution returned no address for '$host'"))
      // Every address is checked: a host may resolve to several, and a client may connect to any of them.
      _ <- addresses.find(isBlockedIP) match {
        case Some(blockedIP) =>
          Left(refused(field, s"Resolved IP '${blockedIP.getHostAddress}' for host '$host' is in a blocked range"))
        case None => Right(())
      }
    } yield addresses

  /** A policy refusal: not retryable, since the same URL or host is refused every time. */
  private def refused(field: String, reason: String): LLMError = ValidationError(field, reason)
}
