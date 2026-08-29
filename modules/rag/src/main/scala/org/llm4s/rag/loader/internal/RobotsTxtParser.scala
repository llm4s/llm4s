package org.llm4s.rag.loader.internal

import java.net.{ HttpURLConnection, URI }
import java.util.regex.Pattern
import scala.collection.mutable
import scala.io.Source
import scala.util.{ Try, Using }

/**
 * Parser and cache for robots.txt files.
 *
 * Supports:
 * - User-agent directive
 * - Disallow directive
 * - Allow directive
 * - Crawl-delay directive
 * - Wildcard patterns (* and $)
 */
object RobotsTxtParser {

  final private case class ParsedUrl(
    scheme: String,
    host: String,
    port: Int,
    path: String
  )

  final private case class RobotsGroup(
    userAgents: Seq[String],
    allowRules: Seq[String],
    disallowRules: Seq[String],
    crawlDelay: Option[Int]
  )

  /**
   * Parsed robots.txt rules for a domain.
   *
   * @param allowRules Paths that are explicitly allowed
   * @param disallowRules Paths that are disallowed
   * @param crawlDelay Suggested delay between requests in seconds
   */
  final case class RobotsTxt(
    allowRules: Seq[String] = Seq.empty,
    disallowRules: Seq[String] = Seq.empty,
    crawlDelay: Option[Int] = None
  ) {

    /**
     * Check if a path is allowed according to these rules.
     *
     * Uses order of specificity: longer matches take precedence.
     * Allow rules take precedence over Disallow rules of equal length.
     */
    def isAllowed(path: String): Boolean = {
      // Find matching rules
      val matchingAllows    = allowRules.filter(r => pathMatches(path, r))
      val matchingDisallows = disallowRules.filter(r => pathMatches(path, r))

      if (matchingAllows.isEmpty && matchingDisallows.isEmpty) {
        true // No rules match, allowed by default
      } else {
        // Get longest matching rule from each category
        val longestAllow    = matchingAllows.maxByOption(_.length)
        val longestDisallow = matchingDisallows.maxByOption(_.length)

        (longestAllow, longestDisallow) match {
          case (None, Some(_))    => false
          case (Some(_), None)    => true
          case (Some(a), Some(d)) => a.length >= d.length // Allow wins ties
          case (None, None)       => true
        }
      }
    }

    /**
     * Check if a path matches a robots.txt rule pattern.
     */
    private def pathMatches(path: String, rule: String): Boolean =
      if (rule.isEmpty || rule == "/") {
        // Empty or root rule matches everything
        true
      } else {
        val anchored    = rule.endsWith("$")
        val patternBody = if (anchored) rule.dropRight(1) else rule
        val regex       = new StringBuilder("^")

        patternBody.foreach {
          case '*' => regex.append(".*")
          case c   => regex.append(Pattern.quote(c.toString))
        }

        if (anchored) regex.append("$") else regex.append(".*")

        Try(path.matches(regex.toString)).getOrElse(false)
      }
  }

  object RobotsTxt {
    val empty: RobotsTxt = RobotsTxt()
  }

  /**
   * Cache for fetched robots.txt files.
   * Keys are scheme/host/port, values are parsed groups.
   */
  private val cache = mutable.Map[String, (Seq[RobotsGroup], Long)]()

  // Cache TTL: 1 hour
  private val cacheTtlMs = 3600000L

  private def cacheKey(parsed: ParsedUrl): String = {
    val portPart = if (parsed.port == -1) "" else s":${parsed.port}"
    s"${parsed.scheme}://${parsed.host}$portPart"
  }

  /**
   * Check if a URL is allowed according to robots.txt.
   *
   * Fetches and caches robots.txt for the domain if not already cached.
   *
   * @param url URL to check
   * @param userAgent User agent string
   * @param timeoutMs Request timeout
   * @return true if URL is allowed
   */
  def isAllowed(url: String, userAgent: String, timeoutMs: Int = 30000): Boolean =
    parseUrl(url)
      .map { parsed =>
        val groups = getOrFetch(parsed, userAgent, timeoutMs)
        val rules  = selectRules(groups, userAgent)
        rules.isAllowed(parsed.path)
      }
      .getOrElse(true) // Allow on error

  /**
   * Get parsed robots.txt rules for a URL.
   *
   * @param url URL to check
   * @param userAgent User agent string
   * @param timeoutMs Request timeout
   * @return Parsed rules for this user agent
   */
  def getRules(url: String, userAgent: String, timeoutMs: Int = 30000): RobotsTxt =
    parseUrl(url)
      .map { parsed =>
        val groups = getOrFetch(parsed, userAgent, timeoutMs)
        selectRules(groups, userAgent)
      }
      .getOrElse(RobotsTxt.empty)

  /**
   * Get cached robots.txt or fetch if expired/missing.
   */
  private def getOrFetch(parsed: ParsedUrl, userAgent: String, timeoutMs: Int): Seq[RobotsGroup] = {
    val now = System.currentTimeMillis()
    val key = cacheKey(parsed)

    cache.get(key) match {
      case Some((groups, fetchTime)) if now - fetchTime < cacheTtlMs =>
        groups // Cache hit
      case _ =>
        val groups = fetch(parsed, userAgent, timeoutMs)
        cache(key) = (groups, now)
        groups
    }
  }

  /**
   * Fetch and parse robots.txt for a domain.
   */
  private def fetch(parsed: ParsedUrl, userAgent: String, timeoutMs: Int): Seq[RobotsGroup] =
    Try {
      val uri  = new URI(parsed.scheme, null, parsed.host, parsed.port, "/robots.txt", null, null)
      val conn = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]

      conn.setConnectTimeout(timeoutMs)
      conn.setReadTimeout(timeoutMs)
      conn.setRequestProperty("User-Agent", userAgent)

      Using.resource(new AutoCloseable {
        override def close(): Unit = conn.disconnect()
      }) { _ =>
        val code = conn.getResponseCode
        if (code == 200) {
          val content = Using.resource(Source.fromInputStream(conn.getInputStream, "UTF-8")) {
            _.mkString
          }
          parseAll(content)
        } else {
          // No robots.txt or error - allow all
          Seq.empty
        }
      }
    }.getOrElse(Seq.empty)

  private def parseUrl(url: String): Option[ParsedUrl] =
    Try {
      val uri    = new URI(url)
      val scheme = Option(uri.getScheme).getOrElse("https").toLowerCase
      val host   = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
      val port   = uri.getPort
      val path   = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
      ParsedUrl(scheme, host, port, path)
    }.toOption.filter(_.host.nonEmpty)

  private def parseAll(content: String): Seq[RobotsGroup] = {
    val groups = mutable.ListBuffer[RobotsGroup]()

    var userAgents              = Vector.empty[String]
    var allowRules              = Vector.empty[String]
    var disallowRules           = Vector.empty[String]
    var crawlDelay: Option[Int] = None
    var sawDirective            = false

    def flush(): Unit = {
      if (userAgents.nonEmpty) {
        groups += RobotsGroup(userAgents, allowRules, disallowRules, crawlDelay)
      }
      userAgents = Vector.empty
      allowRules = Vector.empty
      disallowRules = Vector.empty
      crawlDelay = None
      sawDirective = false
    }

    content.linesIterator.foreach { line =>
      val trimmed = line.split('#').head.trim

      if (trimmed.isEmpty) {
        if (userAgents.nonEmpty) {
          flush()
        }
      } else {
        val parts = trimmed.split(":", 2).map(_.trim)
        if (parts.length == 2) {
          val directive = parts(0).toLowerCase
          val value     = parts(1)

          directive match {
            case "user-agent" =>
              if (userAgents.nonEmpty && sawDirective) {
                flush()
              }
              if (value.nonEmpty) {
                userAgents = userAgents :+ value
              }

            case "disallow" if userAgents.nonEmpty =>
              sawDirective = true
              if (value.nonEmpty) {
                disallowRules = disallowRules :+ value
              }

            case "allow" if userAgents.nonEmpty =>
              sawDirective = true
              if (value.nonEmpty) {
                allowRules = allowRules :+ value
              }

            case "crawl-delay" if userAgents.nonEmpty =>
              sawDirective = true
              crawlDelay = Try(value.toDouble.toInt).toOption.orElse(crawlDelay)

            case _ => // Ignore unknown directives or directives without a group
          }
        }
      }
    }

    flush()
    groups.toSeq
  }

  private def selectRules(groups: Seq[RobotsGroup], targetUserAgent: String): RobotsTxt = {
    val targetLower                      = targetUserAgent.toLowerCase
    var best: Option[(Int, RobotsGroup)] = None

    groups.foreach { group =>
      val bestMatch = group.userAgents.flatMap { ua =>
        val uaLower = ua.toLowerCase
        if (uaLower == "*") Some(1)
        else if (targetLower.startsWith(uaLower)) Some(uaLower.length)
        else None
      }.maxOption

      bestMatch.foreach { length =>
        if (best.forall(_._1 < length)) {
          best = Some(length -> group)
        }
      }
    }

    best
      .map { case (_, group) =>
        RobotsTxt(group.allowRules, group.disallowRules, group.crawlDelay)
      }
      .getOrElse(RobotsTxt.empty)
  }

  /**
   * Parse robots.txt content for a specific user agent.
   *
   * Follows standard robots.txt parsing rules:
   * - Look for matching User-agent group or "*"
   * - Collect Allow/Disallow rules
   * - Parse Crawl-delay
   */
  def parse(content: String, targetUserAgent: String): RobotsTxt =
    selectRules(parseAll(content), targetUserAgent)

  /**
   * Clear the robots.txt cache (for testing or memory management).
   */
  def clearCache(): Unit = cache.clear()
}
