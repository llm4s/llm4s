package org.llm4s.toolapi.builtin.http

import org.llm4s.core.safety.NetworkSecurity

/**
 * Configuration for HTTP tool.
 *
 * == Security ==
 * By default, HTTPTool is configured with safe defaults:
 *  - Only GET and HEAD methods are allowed (read-only)
 *  - Internal IP ranges are blocked (10.x, 172.16-31.x, 192.168.x)
 *  - Cloud metadata endpoints are blocked (169.254.169.254)
 *  - Localhost and loopback addresses are blocked
 *
 * @param allowedDomains Optional list of allowed domains. If None, all domains are allowed.
 * @param blockedDomains List of domains that are always blocked.
 * @param blockInternalIPs Whether to block requests to internal/private IP ranges (default: true).
 * @param maxResponseSize Maximum response size in bytes.
 * @param timeoutMs Request timeout in milliseconds.
 * @param followRedirects Whether to follow HTTP redirects.
 * @param maxRedirects Maximum number of redirects to follow.
 * @param allowedMethods HTTP methods that are allowed (default: GET, HEAD for safety).
 * @param userAgent User-Agent header to use.
 */
case class HttpConfig(
  allowedDomains: Option[Seq[String]] = None,
  blockedDomains: Seq[String] = HttpConfig.DefaultBlockedDomains,
  blockInternalIPs: Boolean = true,
  maxResponseSize: Long = 10 * 1024 * 1024, // 10 MB
  timeoutMs: Int = 30000,                   // 30 seconds
  followRedirects: Boolean = true,
  maxRedirects: Int = 5,
  allowedMethods: Seq[String] = Seq("GET", "HEAD"), // Safe default: read-only
  userAgent: String = "llm4s-http-tool/1.0"
) {

  /**
   * Check if a domain is allowed based on blocklist/allowlist configuration.
   *
   * This method performs hostname-based checks only:
   * 1. Hostname-based blocklist check
   * 2. Allowlist check (if configured)
   *
   * Note: IP-based SSRF protection (DNS resolution + IP range validation) is performed
   * at request time by the HTTP tool to avoid expensive DNS lookups during validation.
   */
  def isDomainAllowed(domain: String): Boolean = {
    val normalizedDomain = domain.toLowerCase.stripPrefix("www.")

    // Check blocked domains first
    val isBlocked = blockedDomains.exists { blocked =>
      normalizedDomain == blocked.toLowerCase || normalizedDomain.endsWith("." + blocked.toLowerCase)
    }

    if (isBlocked) false
    else
      // If allowlist is defined, domain must be in it
      allowedDomains match {
        case Some(allowed) =>
          allowed.exists { a =>
            val normalizedAllowed = a.toLowerCase.stripPrefix("www.")
            normalizedDomain == normalizedAllowed || normalizedDomain.endsWith("." + normalizedAllowed)
          }
        case None => true
      }
  }

  /**
   * Validate a domain with full SSRF protection including DNS resolution.
   *
   * This performs both hostname-based checks and IP-based SSRF protection.
   * Use this at request time when actually making HTTP requests.
   *
   * @param domain The domain to validate
   * @return true if the domain is safe to access
   */
  def validateDomainWithSSRF(domain: String): Boolean =
    // First check hostname-based rules
    if (!isDomainAllowed(domain)) false
    else if (blockInternalIPs) NetworkSecurity.validateHostname(domain).isRight
    else true

  /**
   * Check if a method is allowed.
   */
  def isMethodAllowed(method: String): Boolean =
    allowedMethods.map(_.toUpperCase).contains(method.toUpperCase)

  /**
   * Create a copy with all HTTP methods enabled.
   *
   * WARNING: This enables potentially destructive methods (POST, PUT, DELETE).
   * Only use this when you trust the LLM's judgment and have appropriate safeguards.
   */
  def withAllMethods: HttpConfig =
    copy(allowedMethods = Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"))

  /**
   * Create a copy with internal IP blocking disabled.
   *
   * WARNING: This allows requests to internal networks and cloud metadata endpoints.
   * Only use this in controlled environments where SSRF is not a concern.
   */
  def withInternalIPsAllowed: HttpConfig =
    copy(blockInternalIPs = false)
}

object HttpConfig {

  /**
   * Default blocked domains (hostnames).
   */
  val DefaultBlockedDomains: Seq[String] = Seq(
    "localhost",
    "localhost.localdomain",
    "127.0.0.1",
    "0.0.0.0",
    "::1",
    "[::1]",
    "metadata.google.internal", // GCP metadata
    "metadata.internal",        // Azure metadata
    "169.254.169.254"           // AWS/GCP/Azure metadata IP
  )

  /**
   * Create a read-only configuration that only allows GET and HEAD requests.
   * This is the default and safest configuration.
   */
  def readOnly(
    allowedDomains: Option[Seq[String]] = None,
    blockedDomains: Seq[String] = DefaultBlockedDomains
  ): HttpConfig =
    HttpConfig(
      allowedDomains = allowedDomains,
      blockedDomains = blockedDomains,
      allowedMethods = Seq("GET", "HEAD")
    )

  /**
   * Create a restrictive configuration with explicit domain allowlist.
   */
  def restricted(allowedDomains: Seq[String]): HttpConfig =
    HttpConfig(allowedDomains = Some(allowedDomains))

  /**
   * Create a configuration that allows all common HTTP methods.
   *
   * WARNING: This enables potentially destructive methods (POST, PUT, DELETE).
   * Use with caution and appropriate guardrails.
   */
  def withWriteMethods(
    allowedDomains: Option[Seq[String]] = None,
    blockedDomains: Seq[String] = DefaultBlockedDomains
  ): HttpConfig =
    HttpConfig(
      allowedDomains = allowedDomains,
      blockedDomains = blockedDomains,
      allowedMethods = Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    )

  /**
   * Create an unsafe configuration that disables SSRF protection.
   *
   * WARNING: This allows requests to internal networks and cloud metadata endpoints.
   * Only use this in controlled/sandboxed environments.
   */
  def unsafe: HttpConfig =
    HttpConfig(
      blockedDomains = Seq.empty,
      blockInternalIPs = false,
      allowedMethods = Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
    )
}
