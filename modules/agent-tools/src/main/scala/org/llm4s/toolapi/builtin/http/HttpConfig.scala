package org.llm4s.toolapi.builtin.http

import org.llm4s.core.safety.NetworkSecurity

import java.util.Locale
import scala.concurrent.duration.*

/**
 * Configuration for HTTP tool.
 *
 * == Security ==
 * By default, HTTPTool is configured with safe defaults:
 *  - Only GET and HEAD methods are allowed (read-only)
 *  - Internal IP ranges are blocked (10.x, 172.16-31.x, 192.168.x, IPv6 unique-local fc00::/7, ...)
 *  - Cloud metadata endpoints are blocked (169.254.169.254)
 *  - Localhost and loopback addresses are blocked
 *
 * @param allowedDomains Optional list of allowed domains. If None, all domains are allowed.
 * @param blockedDomains List of domains that are always blocked.
 * @param blockInternalIPs Whether to block requests to internal/private IP ranges (default: true).
 * @param maxResponseSize Maximum number of response body bytes read; the rest is never read, and the
 *                        result's `truncated` is `true`.
 * @param timeout Deadline for the whole call: connecting, every redirect hop and reading the body must all finish
 *                within it (the SSRF check's name resolution too), so a server that sends slowly cannot hold the
 *                call longer. A call that runs out of time fails with a `TIMEOUT:` error. A zero or negative timeout
 *                fails every call; one beyond 100 years is treated as 100 years. A `TIMEOUT:` releases the caller,
 *                not the request: one already sent may still be delivered and acted on by the server (a write is
 *                not rolled back), and a DNS lookup cannot be interrupted, so the worker thread may outlive the
 *                deadline by up to the resolver's own timeout ([[https://github.com/llm4s/llm4s/issues/1734 #1734]]).
 * @param followRedirects Whether to follow HTTP redirects.  Defaults to `false`; when
 *                        `true` each redirect hop is re-validated against the SSRF filter
 *                        before the next request is issued (open-redirect bypass prevention).
 * @param maxRedirects Maximum number of redirects to follow.
 * @param allowedMethods HTTP methods that are allowed (default: GET, HEAD for safety).
 * @param userAgent User-Agent header to use.
 * @param redirectSafeHeaders The caller-set headers a redirect may carry to another origin (matched in any case).
 *                        Once a hop leaves the original request's origin - its scheme, host and port, so a downgrade
 *                        from `https` to `http` counts - every other header the caller set is dropped, on that hop and
 *                        every later one, including one that comes back. The default is `Accept`, `Accept-Language`,
 *                        `Accept-Encoding`, `User-Agent` and `Content-Type`, the last only on a hop that re-sends the
 *                        body (a 307 or 308). A header that names a credential (`Authorization`, `Cookie`,
 *                        `Proxy-Authorization`, any name core's redaction treats as sensitive, such as `X-Api-Key` or
 *                        `X-Client-Secret`, and any name ending in `token` or `key`) is dropped even if listed here.
 *                        `Seq.empty` forwards no caller-set header.
 */
case class HttpConfig(
  allowedDomains: Option[Seq[String]] = None,
  blockedDomains: Seq[String] = HttpConfig.DefaultBlockedDomains,
  blockInternalIPs: Boolean = true,
  maxResponseSize: Long = 10 * 1024 * 1024, // 10 MB
  timeout: FiniteDuration = 30.seconds,
  followRedirects: Boolean = false, // Secure default: redirects are followed only when explicitly opted-in.
  maxRedirects: Int = 5,
  allowedMethods: Seq[String] = Seq("GET", "HEAD"), // Safe default: read-only
  userAgent: String = "llm4s-http-tool/1.0",
  redirectSafeHeaders: Seq[String] = HttpConfig.DefaultRedirectSafeHeaders
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
    val normalizedDomain = domain.toLowerCase(Locale.ROOT).stripPrefix("www.")

    // Check blocked domains first
    val isBlocked = blockedDomains.exists { blocked =>
      val b = blocked.toLowerCase(Locale.ROOT)
      normalizedDomain == b || normalizedDomain.endsWith(s".$b")
    }

    if (isBlocked) false
    else
      // If allowlist is defined, domain must be in it
      allowedDomains match {
        case Some(allowed) =>
          allowed.exists { a =>
            val normalizedAllowed = a.toLowerCase(Locale.ROOT).stripPrefix("www.")
            normalizedDomain == normalizedAllowed || normalizedDomain.endsWith(s".$normalizedAllowed")
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
    allowedMethods.map(_.toUpperCase(Locale.ROOT)).contains(method.toUpperCase(Locale.ROOT))

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

  /**
   * Create a copy with redirect following enabled.
   *
   * Each redirect hop is re-validated against the SSRF filter; from the first hop that leaves
   * the original origin (scheme, host and port) and on every hop after it, only the caller-set
   * headers on `redirectSafeHeaders` are sent; and 301/302 redirects convert POST to GET per
   * the HTTP specification.
   */
  def withRedirectsEnabled: HttpConfig =
    copy(followRedirects = true)
}

object HttpConfig {

  /**
   * The caller-set headers a redirect carries to another origin by default: content negotiation and the user agent,
   * none of which can carry a credential. `Content-Type` goes only with a re-sent body.
   */
  val DefaultRedirectSafeHeaders: Seq[String] =
    Seq("Accept", "Accept-Language", "Accept-Encoding", "User-Agent", "Content-Type")

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
