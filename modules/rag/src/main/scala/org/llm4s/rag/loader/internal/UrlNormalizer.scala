package org.llm4s.rag.loader.internal

import java.net.URI
import scala.util.Try

/**
 * Utility for normalizing URLs to ensure consistent deduplication.
 *
 * Handles:
 * - Scheme normalization (lowercase)
 * - Host normalization (lowercase)
 * - Path normalization (remove trailing slash, decode/encode consistently)
 * - Fragment removal
 * - Optional query parameter handling
 */
object UrlNormalizer {

  /**
   * Normalize a URL for comparison and deduplication.
   *
   * @param url URL string to normalize
   * @param includeQueryParams Whether to keep query parameters
   * @return Normalized URL string, or original if parsing fails
   */
  def normalize(url: String, includeQueryParams: Boolean = false): String =
    Try {
      val uri = new URI(url)

      // Normalize components
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("http")
      val host   = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
      val port   = normalizePort(scheme, uri.getPort)
      val path   = normalizePath(Option(uri.getRawPath).getOrElse(""))
      val query  = if (includeQueryParams) Option(uri.getRawQuery) else None

      // Reconstruct URI without fragment
      val portPart  = port.map(p => s":$p").getOrElse("")
      val queryPart = query.map(q => s"?$q").getOrElse("")

      s"$scheme://$host$portPart$path$queryPart"
    }.getOrElse(url)

  /**
   * Resolve a potentially relative URL against a base URL.
   *
   * @param baseUrl Base URL (page the link was found on)
   * @param href Link href (may be relative or absolute)
   * @param includeQueryParams Whether to keep query parameters
   * @return Resolved and normalized absolute URL
   */
  def resolve(baseUrl: String, href: String, includeQueryParams: Boolean = false): Option[String] =
    Try {
      val base     = new URI(baseUrl)
      val resolved = base.resolve(href)
      normalize(resolved.toString, includeQueryParams)
    }.toOption

  /**
   * Extract the domain (host) from a URL.
   *
   * @param url URL to extract domain from
   * @return Domain string (lowercase), or None if invalid
   */
  def extractDomain(url: String): Option[String] =
    Try {
      new URI(url).getHost.toLowerCase
    }.toOption

  /**
   * Check if a URL belongs to one of the allowed domains.
   *
   * @param url URL to check
   * @param allowedDomains Set of allowed domains
   * @return true if URL's domain matches or is subdomain of an allowed domain
   */
  def isInDomains(url: String, allowedDomains: Set[String]): Boolean =
    extractDomain(url).exists { domain =>
      allowedDomains.exists(allowed => domain == allowed || domain.endsWith(s".$allowed"))
    }

  /**
   * Check if a URL is a valid HTTP/HTTPS URL.
   *
   * @param url URL to validate
   * @return true if URL is valid HTTP or HTTPS
   */
  def isValidHttpUrl(url: String): Boolean =
    Try {
      val uri    = new URI(url)
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      scheme.exists(s => s == "http" || s == "https") && uri.getHost != null
    }.getOrElse(false)

  /**
   * Normalize port number, returning None for default ports.
   */
  private def normalizePort(scheme: String, port: Int): Option[Int] =
    (scheme, port) match {
      case (_, -1)        => None // No port specified
      case ("http", 80)   => None // Default HTTP
      case ("https", 443) => None // Default HTTPS
      case (_, p)         => Some(p)
    }

  /**
   * Normalize path component.
   */
  private def normalizePath(path: String): String = {
    val normalized = path
      .replaceAll("/+", "/") // Collapse multiple slashes
      .stripSuffix("/")      // Remove trailing slash

    if (normalized.isEmpty) "/" else normalized
  }
}
