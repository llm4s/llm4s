package org.llm4s.rag.loader

/**
 * Configuration for web crawling.
 *
 * Controls how the WebCrawlerLoader discovers and fetches web pages.
 *
 * @param maxDepth Maximum link depth to follow from seed URLs (0 = seed URLs only)
 * @param maxPages Maximum total pages to crawl
 * @param followPatterns URL patterns to follow (glob syntax with asterisk wildcards)
 * @param excludePatterns URL patterns to exclude (glob syntax)
 * @param respectRobotsTxt Whether to respect robots.txt directives
 * @param delayMs Delay between requests in milliseconds (rate limiting)
 * @param timeoutMs HTTP request timeout in milliseconds
 * @param userAgent User agent string for HTTP requests
 * @param maxQueueSize Maximum number of URLs to queue (prevents unbounded memory usage)
 * @param includeQueryParams Whether to treat URLs with different query params as distinct pages
 * @param sameDomainOnly Whether to restrict crawling to the same domain as seed URLs
 * @param acceptContentTypes Content types to process (others are skipped)
 */
final case class CrawlerConfig(
  maxDepth: Int = 3,
  maxPages: Int = 1000,
  followPatterns: Seq[String] = Seq("*"),
  excludePatterns: Seq[String] = Seq.empty,
  respectRobotsTxt: Boolean = true,
  delayMs: Int = 500,
  timeoutMs: Int = 30000,
  userAgent: String = "LLM4S-Crawler/1.0",
  maxQueueSize: Int = 10000,
  includeQueryParams: Boolean = false,
  sameDomainOnly: Boolean = true,
  acceptContentTypes: Set[String] = Set("text/html", "application/xhtml+xml")
) {

  /** Set max crawl depth */
  def withMaxDepth(depth: Int): CrawlerConfig =
    copy(maxDepth = depth)

  /** Set max pages to crawl */
  def withMaxPages(pages: Int): CrawlerConfig =
    copy(maxPages = pages)

  /** Set URL patterns to follow */
  def withFollowPatterns(patterns: String*): CrawlerConfig =
    copy(followPatterns = patterns)

  /** Set URL patterns to exclude */
  def withExcludePatterns(patterns: String*): CrawlerConfig =
    copy(excludePatterns = patterns)

  /** Set rate limit delay */
  def withDelay(ms: Int): CrawlerConfig =
    copy(delayMs = ms)

  /** Set request timeout */
  def withTimeout(ms: Int): CrawlerConfig =
    copy(timeoutMs = ms)

  /** Set user agent */
  def withUserAgent(ua: String): CrawlerConfig =
    copy(userAgent = ua)

  /** Set whether to respect robots.txt */
  def withRobotsTxt(respect: Boolean): CrawlerConfig =
    copy(respectRobotsTxt = respect)

  /** Set whether to include query parameters in URL comparison */
  def withQueryParams(include: Boolean): CrawlerConfig =
    copy(includeQueryParams = include)

  /** Set whether to restrict to same domain */
  def withSameDomainOnly(enabled: Boolean): CrawlerConfig =
    copy(sameDomainOnly = enabled)

  /** Set max queue size */
  def withMaxQueueSize(size: Int): CrawlerConfig =
    copy(maxQueueSize = size)

  /** Set acceptable content types */
  def withContentTypes(types: Set[String]): CrawlerConfig =
    copy(acceptContentTypes = types)
}

object CrawlerConfig {

  /** Default configuration with sensible defaults */
  val default: CrawlerConfig = CrawlerConfig()

  /** Conservative configuration for polite crawling */
  val polite: CrawlerConfig = CrawlerConfig(
    maxDepth = 2,
    maxPages = 100,
    delayMs = 1000,
    respectRobotsTxt = true
  )

  /** Aggressive configuration for faster crawling */
  val fast: CrawlerConfig = CrawlerConfig(
    maxDepth = 5,
    maxPages = 5000,
    delayMs = 100,
    respectRobotsTxt = true
  )

  /** Single page configuration (seed URLs only) */
  val singlePage: CrawlerConfig = CrawlerConfig(
    maxDepth = 0,
    maxPages = 1
  )
}
