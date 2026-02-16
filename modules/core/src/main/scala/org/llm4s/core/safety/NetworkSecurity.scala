package org.llm4s.core.safety

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
 *  - Private networks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (RFC 1918)
 *  - Loopback: 127.0.0.0/8, ::1
 *  - Link-local: 169.254.0.0/16, fe80::/10
 *  - Cloud metadata: 169.254.169.254 (AWS, GCP, Azure)
 *  - Multicast: 224.0.0.0/4, ff00::/8
 *  - Documentation/test ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
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

    // Check common blocked conditions
    ip.isLoopbackAddress ||
    ip.isLinkLocalAddress ||
    ip.isSiteLocalAddress ||
    ip.isMulticastAddress ||
    ip.isAnyLocalAddress ||
    isCloudMetadata(ip) ||
    isDocumentationRange(address) ||
    isCarrierGradeNAT(address) ||
    isBenchmarkRange(address)
  }

  /**
   * Check if an IP is the cloud metadata endpoint.
   */
  private def isCloudMetadata(ip: InetAddress): Boolean =
    ip.getHostAddress == CloudMetadataIP

  /**
   * Check if an IPv4 address is in documentation ranges (RFC 5737).
   * TEST-NET-1: 192.0.2.0/24
   * TEST-NET-2: 198.51.100.0/24
   * TEST-NET-3: 203.0.113.0/24
   */
  private def isDocumentationRange(address: Array[Byte]): Boolean =
    if (address.length == 4) {
      val b0 = address(0) & 0xff
      val b1 = address(1) & 0xff
      val b2 = address(2) & 0xff

      // 192.0.2.0/24
      (b0 == 192 && b1 == 0 && b2 == 2) ||
      // 198.51.100.0/24
      (b0 == 198 && b1 == 51 && b2 == 100) ||
      // 203.0.113.0/24
      (b0 == 203 && b1 == 0 && b2 == 113)
    } else {
      false
    }

  /**
   * Check if an IPv4 address is in Carrier-Grade NAT range (RFC 6598).
   * 100.64.0.0/10
   */
  private def isCarrierGradeNAT(address: Array[Byte]): Boolean =
    if (address.length == 4) {
      val b0 = address(0) & 0xff
      val b1 = address(1) & 0xff
      // 100.64.0.0/10 means 100.64.0.0 - 100.127.255.255
      b0 == 100 && (b1 >= 64 && b1 <= 127)
    } else {
      false
    }

  /**
   * Check if an IPv4 address is in benchmark range (RFC 2544).
   * 198.18.0.0/15
   */
  private def isBenchmarkRange(address: Array[Byte]): Boolean =
    if (address.length == 4) {
      val b0 = address(0) & 0xff
      val b1 = address(1) & 0xff
      // 198.18.0.0/15 means 198.18.0.0 - 198.19.255.255
      b0 == 198 && (b1 == 18 || b1 == 19)
    } else {
      false
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

    allBlocked.exists(blocked => normalizedHostname == blocked || normalizedHostname.endsWith("." + blocked))
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
