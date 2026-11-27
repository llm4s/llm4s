package org.llm4s.rag.loader.internal

import org.llm4s.rag.loader.UrlPolicy

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Parser and cache for robots.txt files, following RFC 9309 (#2999).
 *
 * Supports:
 * - User-agent, Allow, Disallow and Crawl-delay; other records are ignored
 * - Groups: one or more User-agent lines and the rules after them. Blank and comment lines are ignored, so they never
 *   end a group; a User-agent line that follows a rule starts the next one. Every group naming the crawler is merged.
 * - Rules matched against the URL's path and query, with `*` and a trailing `$`, after percent-encoding both sides
 *   the same way; the longest matching rule wins, and an Allow wins a tie
 * - A robots.txt that answers 4xx allows everything; one that answers 5xx, or cannot be fetched, disallows everything
 *   until it can be (it is not cached)
 */
object RobotsTxtParser {

  final private case class ParsedUrl(
    scheme: String,
    host: String,
    port: Int,
    pathAndQuery: String
  )

  final private case class RobotsGroup(
    userAgents: Seq[String],
    allowRules: Seq[String],
    disallowRules: Seq[String],
    crawlDelay: Option[FiniteDuration]
  )

  /** A rule compiled once: its pattern percent-normalised, wildcards collapsed, and whether `$` anchors it. */
  final private case class Rule(pattern: String, anchored: Boolean) {

    /** RFC 9309 2.2.2: the most specific match is the one with the most octets. */
    def length: Int = pattern.length + (if (anchored) 1 else 0)

    def matches(target: String): Boolean = wildcardMatch(if (anchored) pattern else pattern + "*", target)
  }

  private object Rule {
    def compile(raw: String): Rule = {
      val anchored = raw.endsWith("$")
      val body     = normalise(if (anchored) raw.dropRight(1) else raw).replaceAll("\\*+", "*")
      Rule(body, anchored)
    }
  }

  /**
   * Parsed robots.txt rules for a domain.
   *
   * @param allowRules Paths that are explicitly allowed
   * @param disallowRules Paths that are disallowed
   * @param crawlDelay Suggested delay between requests (robots.txt gives it in seconds, possibly fractional)
   */
  final case class RobotsTxt(
    allowRules: Seq[String] = Seq.empty,
    disallowRules: Seq[String] = Seq.empty,
    crawlDelay: Option[FiniteDuration] = None
  ) {

    private lazy val allows: Seq[Rule]    = allowRules.map(Rule.compile)
    private lazy val disallows: Seq[Rule] = disallowRules.map(Rule.compile)

    /**
     * Check if a path, with its query if it has one (`/search?q=x`), is allowed according to these rules.
     *
     * Uses order of specificity: longer matches take precedence.
     * Allow rules take precedence over Disallow rules of equal length.
     */
    def isAllowed(path: String): Boolean = {
      val target                                 = normalise(if (path.isEmpty) "/" else path)
      def longest(rules: Seq[Rule]): Option[Int] = rules.filter(_.matches(target)).map(_.length).maxOption

      longest(disallows) match {
        case None           => true
        case Some(disallow) => longest(allows).exists(_ >= disallow) // Allow wins ties
      }
    }
  }

  object RobotsTxt {
    val empty: RobotsTxt = RobotsTxt()

    /** What an unreachable robots.txt means (RFC 9309 2.3.1.4): nothing may be crawled. */
    val disallowAll: RobotsTxt = RobotsTxt(disallowRules = Seq("/"))
  }

  /** The most hosts whose robots.txt is cached; the least recently used is dropped first. */
  private val MaxCachedHosts = 1024

  /**
   * Cache for fetched robots.txt files, shared by every crawl in the process, so bounded and thread-safe.
   * Keys are scheme/host/port, values are parsed groups and when they were fetched.
   */
  private val cache = new BoundedCache[String, (Seq[RobotsGroup], Long)](MaxCachedHosts)

  // Cache TTL: 1 hour
  private val cacheTtlMs = 3600000L

  private def cacheKey(parsed: ParsedUrl): String = {
    val portPart = if (parsed.port == -1) "" else s":${parsed.port}"
    s"${parsed.scheme}://${parsed.host}$portPart"
  }

  /**
   * Check if a URL (its path and query) is allowed according to robots.txt.
   *
   * Fetches and caches robots.txt for the domain if not already cached. A URL that cannot be parsed is allowed; one
   * whose robots.txt is unreachable (5xx, network error) is not.
   *
   * @param url URL to check
   * @param userAgent User agent string
   * @param timeout Request timeout
   * @param policy Which URLs the robots.txt fetch may request, and where it connects
   * @return true if URL is allowed
   */
  def isAllowed(
    url: String,
    userAgent: String,
    timeout: FiniteDuration = 30.seconds,
    policy: UrlPolicy = UrlPolicy.default
  ): Boolean =
    parseUrl(url)
      .map { parsed =>
        getOrFetch(parsed, userAgent, timeout, policy) match {
          case Right(groups) => selectRules(groups, userAgent).isAllowed(parsed.pathAndQuery)
          case Left(_)       => false
        }
      }
      .getOrElse(true) // Allow on error

  /**
   * Get parsed robots.txt rules for a URL.
   *
   * @param url URL to check
   * @param userAgent User agent string
   * @param timeout Request timeout
   * @param policy Which URLs the robots.txt fetch may request, and where it connects
   * @return Parsed rules for this user agent; [[RobotsTxt.disallowAll]] while robots.txt is unreachable
   */
  def getRules(
    url: String,
    userAgent: String,
    timeout: FiniteDuration = 30.seconds,
    policy: UrlPolicy = UrlPolicy.default
  ): RobotsTxt =
    rulesFor(url, userAgent, timeout, policy).getOrElse(RobotsTxt.disallowAll)

  /**
   * The rules for a URL, or why its robots.txt is unreachable (a 5xx answer or a failed request), in which case
   * nothing may be crawled from that host for now.
   */
  private[loader] def rulesFor(
    url: String,
    userAgent: String,
    timeout: FiniteDuration,
    policy: UrlPolicy
  ): Either[String, RobotsTxt] =
    parseUrl(url) match {
      case Some(parsed) => getOrFetch(parsed, userAgent, timeout, policy).map(selectRules(_, userAgent))
      case None         => Right(RobotsTxt.empty)
    }

  /** The part of a URL robots.txt rules are matched against: its raw path (`/` when empty) and query. */
  private[loader] def pathAndQuery(uri: URI): String = {
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    Option(uri.getRawQuery).fold(path)(query => s"$path?$query")
  }

  /** The outcome of fetching a robots.txt. */
  private enum Fetched {
    case Groups(groups: Seq[RobotsGroup])
    case Unreachable(reason: String)
    case Refused
  }

  /**
   * Get cached robots.txt or fetch if expired/missing. Left while it is unreachable, which is never cached.
   */
  private def getOrFetch(
    parsed: ParsedUrl,
    userAgent: String,
    timeout: FiniteDuration,
    policy: UrlPolicy
  ): Either[String, Seq[RobotsGroup]] = {
    val now = System.currentTimeMillis()
    val key = cacheKey(parsed)

    cache.get(key) match {
      case Some((groups, fetchTime)) if now - fetchTime < cacheTtlMs =>
        Right(groups) // Cache hit
      case _ =>
        fetch(parsed, userAgent, timeout, policy) match {
          case Fetched.Groups(groups) =>
            cache.put(key, (groups, now))
            Right(groups)
          case Fetched.Unreachable(reason) => Left(reason)
          // Refused by the policy: not cached, so a policy that allows it still fetches. The page itself is
          // checked by the same policy.
          case Fetched.Refused => Right(Seq.empty)
        }
    }
  }

  /**
   * Fetch and parse robots.txt for a domain. The URL and every redirect hop are checked by `policy`, and the request
   * goes only to an address it approved. A 2xx answer is parsed, a 4xx allows everything (RFC 9309 2.3.1.3), and a
   * 5xx or failed request leaves it unreachable (2.3.1.4).
   */
  private def fetch(
    parsed: ParsedUrl,
    userAgent: String,
    timeout: FiniteDuration,
    policy: UrlPolicy
  ): Fetched =
    Try(new URI(parsed.scheme, null, parsed.host, parsed.port, "/robots.txt", null, null)).toOption match {
      case None => Fetched.Groups(Seq.empty)
      // Asked first, so a custom policy's refusal is recognised whatever error it returns.
      case Some(uri) if policy.resolve(uri).isLeft => Fetched.Refused
      case Some(uri) =>
        PinnedHttpClient.get(uri.toString, Seq("User-Agent" -> userAgent), timeout, policy) match {
          case Left(err) if err.endpoint == "ssrf-protection" => Fetched.Refused
          case Left(err)                                      => Fetched.Unreachable(s"$uri: ${err.message}")
          case Right(resp) if resp.status >= 200 && resp.status < 300 =>
            Fetched.Groups(Try(parseAll(resp.body)).getOrElse(Seq.empty))
          case Right(resp) if resp.status >= 400 && resp.status < 500 => Fetched.Groups(Seq.empty)
          case Right(resp) => Fetched.Unreachable(s"$uri answered HTTP ${resp.status}")
        }
    }

  private def parseUrl(url: String): Option[ParsedUrl] =
    Try {
      val uri    = new URI(url)
      val scheme = Option(uri.getScheme).getOrElse("https").toLowerCase
      val host   = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
      ParsedUrl(scheme, host, uri.getPort, pathAndQuery(uri))
    }.toOption.filter(_.host.nonEmpty)

  /** A `Crawl-delay` value: seconds, possibly fractional. Negative, non-finite or unparseable values are ignored. */
  private def parseCrawlDelay(value: String): Option[FiniteDuration] =
    Try(value.toDouble).toOption
      .filter(seconds => seconds >= 0 && !seconds.isInfinite)
      .flatMap(seconds => Try((seconds * 1000).round.millis).toOption)

  private def parseAll(content: String): Seq[RobotsGroup] = {
    val groups = mutable.ListBuffer[RobotsGroup]()

    var userAgents                         = Vector.empty[String]
    var allowRules                         = Vector.empty[String]
    var disallowRules                      = Vector.empty[String]
    var crawlDelay: Option[FiniteDuration] = None
    var sawRule                            = false

    def flush(): Unit = {
      if (userAgents.nonEmpty) {
        groups += RobotsGroup(userAgents, allowRules, disallowRules, crawlDelay)
      }
      userAgents = Vector.empty
      allowRules = Vector.empty
      disallowRules = Vector.empty
      crawlDelay = None
      sawRule = false
    }

    content.stripPrefix("﻿").linesIterator.foreach { line =>
      val trimmed = line.takeWhile(_ != '#').trim

      // A blank or comment line is ignored (RFC 9309 2.2): it never ends a group
      if (trimmed.nonEmpty) {
        val parts = trimmed.split(":", 2).map(_.trim)
        if (parts.length == 2) {
          val directive = parts(0).toLowerCase
          val value     = parts(1)

          directive match {
            case "user-agent" =>
              // A User-agent line after a rule starts the next group; consecutive ones share a group
              if (sawRule) {
                flush()
              }
              if (value.nonEmpty) {
                userAgents = userAgents :+ value
              }

            case "disallow" if userAgents.nonEmpty =>
              sawRule = true
              if (value.nonEmpty) {
                disallowRules = disallowRules :+ value
              }

            case "allow" if userAgents.nonEmpty =>
              sawRule = true
              if (value.nonEmpty) {
                allowRules = allowRules :+ value
              }

            case "crawl-delay" if userAgents.nonEmpty =>
              sawRule = true
              crawlDelay = parseCrawlDelay(value).orElse(crawlDelay)

            case _ => // Ignore unknown directives or directives without a group
          }
        }
      }
    }

    flush()
    groups.toSeq
  }

  /**
   * The rules of the groups that best match the user agent, merged (RFC 9309 2.2.1): the groups naming the longest
   * prefix of it, else the `*` groups.
   */
  private def selectRules(groups: Seq[RobotsGroup], targetUserAgent: String): RobotsTxt = {
    val targetLower = targetUserAgent.toLowerCase

    def score(group: RobotsGroup): Option[Int] = group.userAgents.flatMap { ua =>
      val uaLower = ua.toLowerCase
      if (uaLower == "*") Some(0)
      else if (targetLower.startsWith(uaLower)) Some(uaLower.length)
      else None
    }.maxOption

    val scored = groups.flatMap(group => score(group).map(_ -> group))
    scored.map(_._1).maxOption match {
      case None => RobotsTxt.empty
      case Some(best) =>
        val chosen = scored.collect { case (s, group) if s == best => group }
        RobotsTxt(chosen.flatMap(_.allowRules), chosen.flatMap(_.disallowRules), chosen.flatMap(_.crawlDelay).maxOption)
    }
  }

  /**
   * Percent-encode a path, query or rule the same way, so they compare as RFC 9309 2.2.2 says: an escaped unreserved
   * character is decoded, other escapes are upper-cased, and a character outside printable ASCII is UTF-8 encoded.
   */
  private def normalise(s: String): String = {
    // ASCII only: Character.digit would accept other scripts' digits
    def hex(c: Char): Int =
      if (c >= '0' && c <= '9') c - '0'
      else if (c >= 'A' && c <= 'F') c - 'A' + 10
      else if (c >= 'a' && c <= 'f') c - 'a' + 10
      else -1
    def unreserved(c: Int): Boolean =
      (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ||
        c == '_' || c == '~'

    val out = new StringBuilder(s.length)
    var i   = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '%' && i + 2 < s.length && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
        val value = hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2))
        if (unreserved(value)) out.append(value.toChar)
        else out.append('%').append(f"$value%02X")
        i += 3
      } else if (c > 0x20 && c < 0x7f) {
        out.append(c)
        i += 1
      } else {
        val codePointLength = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
        s.substring(i, i + codePointLength).getBytes(StandardCharsets.UTF_8).foreach { b =>
          out.append('%').append(f"${b & 0xff}%02X")
        }
        i += codePointLength
      }
    }
    out.toString
  }

  /**
   * Whether `text` matches `pattern` in full, where `*` matches any run of characters. Greedy, going back only to the
   * last `*`, so it takes time proportional to the two lengths' product at worst, never exponential.
   */
  private def wildcardMatch(pattern: String, text: String): Boolean = {
    var p      = 0
    var t      = 0
    var star   = -1
    var mark   = 0
    var failed = false
    while (!failed && t < text.length)
      if (p < pattern.length && pattern.charAt(p) == '*') {
        star = p
        mark = t
        p += 1
      } else if (p < pattern.length && pattern.charAt(p) == text.charAt(t)) {
        p += 1
        t += 1
      } else if (star >= 0) {
        p = star + 1
        mark += 1
        t = mark
      } else {
        failed = true
      }
    while (!failed && p < pattern.length && pattern.charAt(p) == '*') p += 1
    !failed && p == pattern.length
  }

  /**
   * Parse robots.txt content for a specific user agent.
   *
   * Follows RFC 9309:
   * - Merge the groups naming the user agent, or else the `*` groups
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
