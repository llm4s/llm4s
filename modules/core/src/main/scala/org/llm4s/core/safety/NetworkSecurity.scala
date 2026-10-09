package org.llm4s.core.safety

import org.llm4s.annotation.Stable
import org.llm4s.error.NetworkError
import org.llm4s.types.Result

import java.net.{ InetAddress, URI }
import scala.util.Try

/**
 * Network security utilities for SSRF protection.
 *
 * Provides IP address validation to prevent Server-Side Request Forgery (SSRF) attacks
 * by blocking requests to internal networks, cloud metadata endpoints, and other
 * potentially sensitive destinations.
 *
 * == Protected IP Ranges ==
 *  - Private networks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (RFC 1918); IPv6 unique-local fc00::/7 (RFC 4193)
 *    and the deprecated site-local fec0::/10
 *  - Loopback: 127.0.0.0/8, ::1
 *  - Unspecified / "this network": 0.0.0.0/8, ::
 *  - Link-local: 169.254.0.0/16, fe80::/10
 *  - Cloud metadata: 169.254.169.254 (AWS, GCP, Azure)
 *  - Multicast: 224.0.0.0/4, ff00::/8
 *  - Carrier-grade NAT 100.64.0.0/10, benchmarking 198.18.0.0/15 and 2001:2::/48
 *  - Documentation/test ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24, 2001:db8::/32, 3fff::/20
 *  - IPv6 discard-only 100::/64, Teredo 2001::/32, local-use NAT64 64:ff9b:1::/48 and the deprecated
 *    IPv4-compatible ::/96
 *  - An IPv6 address that embeds an IPv4 address - IPv4-mapped ::ffff:0:0/96, NAT64 64:ff9b::/96 and
 *    6to4 2002::/16 - is blocked when the embedded IPv4 address is
 *
 * @example
 * {{{
 * import org.llm4s.core.safety.NetworkSecurity
 *
 * // Validate a URL before fetching
 * NetworkSecurity.validateUrl("https://example.com/api") // Right(())
 * NetworkSecurity.validateUrl("http://169.254.169.254/") // Left(NetworkError)
 * NetworkSecurity.validateUrl("http://192.168.1.1/admin") // Left(NetworkError)
 * }}}
 */
@Stable
object NetworkSecurity {

  /**
   * Default blocked hostnames (in addition to IP-based blocking).
   */
  val DefaultBlockedHostnames: Set[String] = Set(
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
    b0 == 0 ||                               // 0.0.0.0/8, "this network" (RFC 1122)
    b0 == 10 ||                              // 10.0.0.0/8 (RFC 1918)
    b0 == 127 ||                             // 127.0.0.0/8 loopback
    (b0 == 169 && b1 == 254) ||              // 169.254.0.0/16 link-local, cloud metadata
    (b0 == 172 && b1 >= 16 && b1 <= 31) ||   // 172.16.0.0/12 (RFC 1918)
    (b0 == 192 && b1 == 168) ||              // 192.168.0.0/16 (RFC 1918)
    (b0 >= 224 && b0 <= 239) ||              // 224.0.0.0/4 multicast
    (b0 == 100 && b1 >= 64 && b1 <= 127) ||  // 100.64.0.0/10 carrier-grade NAT (RFC 6598)
    (b0 == 198 && (b1 == 18 || b1 == 19)) || // 198.18.0.0/15 benchmarking (RFC 2544)
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
   */
  private def isBlockedIPv6(address: Array[Byte]): Boolean = {
    val b0 = u(address, 0)
    val b1 = u(address, 1)
    // ::/96: unspecified, ::1 loopback and the deprecated IPv4-compatible form (RFC 4291 2.5.5.1), never a valid
    // destination
    zeros(address, 0, 12) ||
    // ::ffff:0:0/96 IPv4-mapped (RFC 4291 2.5.5.2): judged by the IPv4 address it carries
    (zeros(address, 0, 10) && u(address, 10) == 0xff && u(address, 11) == 0xff &&
      isBlockedIPv4(embeddedIPv4(address, 12))) ||
    // 64:ff9b::/96 NAT64 well-known prefix (RFC 6052): judged by the embedded IPv4 address
    (startsWith(address, 0x00, 0x64, 0xff, 0x9b) && zeros(address, 4, 12) &&
      isBlockedIPv4(embeddedIPv4(address, 12))) ||
    // 64:ff9b:1::/48 local-use NAT64 (RFC 8215)
    startsWith(address, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x01) ||
    // 100::/64 discard-only (RFC 6666)
    (startsWith(address, 0x01, 0x00) && zeros(address, 2, 8)) ||
    // 2001::/32 Teredo (RFC 4380): tunnels to an address the guard cannot see
    startsWith(address, 0x20, 0x01, 0x00, 0x00) ||
    // 2001:2::/48 benchmarking (RFC 5180)
    startsWith(address, 0x20, 0x01, 0x00, 0x02, 0x00, 0x00) ||
    // 2001:db8::/32 documentation (RFC 3849)
    startsWith(address, 0x20, 0x01, 0x0d, 0xb8) ||
    // 2002::/16 6to4 (RFC 3056): judged by the IPv4 address in bytes 2-5
    (startsWith(address, 0x20, 0x02) && isBlockedIPv4(embeddedIPv4(address, 2))) ||
    // 3fff::/20 documentation (RFC 9637)
    (b0 == 0x3f && b1 == 0xff && (u(address, 2) & 0xf0) == 0) ||
    // fc00::/7 unique-local (RFC 4193)
    (b0 & 0xfe) == 0xfc ||
    // fe80::/10 link-local and fec0::/10 deprecated site-local
    (b0 == 0xfe && (b1 & 0xc0) >= 0x80) ||
    // ff00::/8 multicast
    b0 == 0xff
  }

  /**
   * Check if a hostname should be blocked (case-insensitive).
   *
   * @param hostname The hostname to check
   * @param additionalBlocked Additional hostnames to block
   * @return true if the hostname should be blocked
   */
  def isBlockedHostname(
    hostname: String,
    additionalBlocked: Set[String] = Set.empty
  ): Boolean = {
    val normalizedHostname = hostname.toLowerCase.trim
    val allBlocked         = DefaultBlockedHostnames ++ additionalBlocked.map(_.toLowerCase)

    allBlocked.exists(blocked => normalizedHostname == blocked || normalizedHostname.endsWith(s".$blocked"))
  }

  /**
   * Validate a URL for SSRF safety.
   *
   * This performs DNS resolution and checks if the resolved IP is in a blocked range.
   * It also validates the hostname against known blocked hostnames.
   *
   * @param urlString The URL to validate
   * @param additionalBlockedHostnames Additional hostnames to block
   * @param allowedProtocols Allowed URL protocols (default: http, https)
   * @return Right(()) if safe, Left(NetworkError) if blocked
   */
  def validateUrl(
    urlString: String,
    additionalBlockedHostnames: Set[String] = Set.empty,
    allowedProtocols: Set[String] = Set("http", "https")
  ): Result[Unit] = {
    val result = for {
      // Parse URL
      uri <- Try(new URI(urlString)).toEither.left.map(e => s"Invalid URL: ${e.getMessage}")
      url <- Try(uri.toURL).toEither.left.map(e => s"Cannot convert to URL: ${e.getMessage}")

      // Check protocol
      protocol = Option(url.getProtocol).map(_.toLowerCase).getOrElse("")
      _ <- Either.cond(
        allowedProtocols.contains(protocol),
        (),
        s"Protocol '$protocol' is not allowed. Allowed: ${allowedProtocols.mkString(", ")}"
      )

      // Get hostname
      host = Option(url.getHost).getOrElse("")
      _ <- Either.cond(host.nonEmpty, (), "URL has no host")

      // Check hostname blocklist
      _ <- Either.cond(
        !isBlockedHostname(host, additionalBlockedHostnames),
        (),
        s"Hostname '$host' is blocked"
      )

      // Resolve DNS and check IP
      addresses <- Try(InetAddress.getAllByName(host)).toEither.left
        .map(e => s"DNS resolution failed for '$host': ${e.getMessage}")

      // Check all resolved IPs (some hosts may resolve to multiple addresses)
      _ <- addresses.find(isBlockedIP) match {
        case Some(blockedIP) =>
          Left(s"Resolved IP '${blockedIP.getHostAddress}' for host '$host' is in a blocked range")
        case None =>
          Right(())
      }
    } yield ()

    result.left.map(msg => NetworkError(msg, None, "ssrf-protection"))
  }

  /**
   * Validate a hostname for SSRF safety (without full URL parsing).
   *
   * @param hostname The hostname to validate
   * @param additionalBlockedHostnames Additional hostnames to block
   * @return Right(()) if safe, Left(NetworkError) if blocked
   */
  def validateHostname(
    hostname: String,
    additionalBlockedHostnames: Set[String] = Set.empty
  ): Result[Unit] = {
    val result = for {
      _ <- Either.cond(hostname.nonEmpty, (), "Hostname is empty")

      _ <- Either.cond(
        !isBlockedHostname(hostname, additionalBlockedHostnames),
        (),
        s"Hostname '$hostname' is blocked"
      )

      // Resolve DNS and check IP
      addresses <- Try(InetAddress.getAllByName(hostname)).toEither.left
        .map(e => s"DNS resolution failed for '$hostname': ${e.getMessage}")

      _ <- addresses.find(isBlockedIP) match {
        case Some(blockedIP) =>
          Left(s"Resolved IP '${blockedIP.getHostAddress}' for host '$hostname' is in a blocked range")
        case None =>
          Right(())
      }
    } yield ()

    result.left.map(msg => NetworkError(msg, None, "ssrf-protection"))
  }

  /**
   * Validate an IP address string directly.
   *
   * @param ipString The IP address string to validate
   * @return Right(()) if safe, Left(NetworkError) if blocked
   */
  def validateIP(ipString: String): Result[Unit] = {
    val result = for {
      ip <- Try(InetAddress.getByName(ipString)).toEither.left
        .map(e => s"Invalid IP address '$ipString': ${e.getMessage}")

      _ <- Either.cond(
        !isBlockedIP(ip),
        (),
        s"IP address '$ipString' is in a blocked range"
      )
    } yield ()

    result.left.map(msg => NetworkError(msg, None, "ssrf-protection"))
  }
}
