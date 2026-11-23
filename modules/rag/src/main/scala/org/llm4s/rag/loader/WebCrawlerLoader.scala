package org.llm4s.rag.loader

import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.internal._

import java.net.URI
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Load documents by crawling from seed URLs.
 *
 * Features:
 * - Breadth-first link discovery
 * - Domain/pattern restrictions
 * - robots.txt support
 * - Rate limiting
 * - HTML to text conversion
 * - Deduplication by URL
 *
 * @param seedUrls Starting URLs to crawl from
 * @param config Crawler configuration
 * @param metadata Additional metadata for all documents
 */
final case class WebCrawlerLoader(
  seedUrls: Seq[String],
  config: CrawlerConfig = CrawlerConfig(),
  metadata: Map[String, String] = Map.empty
) extends DocumentLoader {

  // Extract domains from seed URLs for same-domain restriction
  private lazy val seedDomains: Set[String] =
    seedUrls.flatMap(UrlNormalizer.extractDomain).toSet

  def load(): Iterator[LoadResult] = new CrawlingIterator

  override def estimatedCount: Option[Int] = None // Unknown until crawl completes

  def description: String = s"WebCrawlerLoader(${seedUrls.size} seeds, depth=${config.maxDepth})"

  /** Add a seed URL */
  def withSeed(url: String): WebCrawlerLoader =
    copy(seedUrls = seedUrls :+ url)

  /** Add multiple seed URLs */
  def withSeeds(urls: String*): WebCrawlerLoader =
    copy(seedUrls = seedUrls ++ urls)

  /** Add metadata */
  def withMetadata(m: Map[String, String]): WebCrawlerLoader =
    copy(metadata = metadata ++ m)

  /** Set max depth */
  def withMaxDepth(depth: Int): WebCrawlerLoader =
    copy(config = config.withMaxDepth(depth))

  /** Set max pages */
  def withMaxPages(pages: Int): WebCrawlerLoader =
    copy(config = config.withMaxPages(pages))

  /** Set follow patterns */
  def withFollowPatterns(patterns: String*): WebCrawlerLoader =
    copy(config = config.withFollowPatterns(patterns: _*))

  /** Set exclude patterns */
  def withExcludePatterns(patterns: String*): WebCrawlerLoader =
    copy(config = config.withExcludePatterns(patterns: _*))

  /** Set rate limit delay */
  def withDelay(delay: FiniteDuration): WebCrawlerLoader =
    copy(config = config.withDelay(delay))

  /** Set request timeout */
  def withTimeout(timeout: FiniteDuration): WebCrawlerLoader =
    copy(config = config.withTimeout(timeout))

  /** Set whether to respect robots.txt */
  def withRobotsTxt(respect: Boolean): WebCrawlerLoader =
    copy(config = config.withRobotsTxt(respect))

  /** Set whether to restrict to same domain */
  def withSameDomainOnly(enabled: Boolean): WebCrawlerLoader =
    copy(config = config.withSameDomainOnly(enabled))

  /** Set whether to include query parameters */
  def withQueryParams(include: Boolean): WebCrawlerLoader =
    copy(config = config.withQueryParams(include))

  /** Set maximum queue size */
  def withMaxQueueSize(size: Int): WebCrawlerLoader =
    copy(config = config.withMaxQueueSize(size))

  /** Set the policy every seed, crawled link, redirect hop and robots.txt request is checked against */
  def withUrlPolicy(policy: UrlPolicy): WebCrawlerLoader =
    copy(config = config.withUrlPolicy(policy))

  /**
   * Internal crawling iterator that implements BFS.
   */
  private class CrawlingIterator extends Iterator[LoadResult] {
    // Track visited URLs (normalized)
    private val visited = mutable.Set[String]()

    // BFS queue: (fetch URL, canonical URL, depth)
    private val queue = mutable.Queue[(String, String, Int)]()

    // Track page count
    private var pageCount = 0

    // Flag for first request (skip delay)
    private var isFirstRequest = true

    // Initialize queue with seed URLs
    seedUrls.foreach { url =>
      val normalized = UrlNormalizer.normalize(url, config.includeQueryParams)
      if (!visited.contains(normalized) && UrlNormalizer.isValidHttpUrl(normalized)) {
        visited += normalized
        queue.enqueue((url, normalized, 0))
      }
    }

    def hasNext: Boolean =
      queue.nonEmpty && pageCount < config.maxPages

    def next(): LoadResult = {
      if (!hasNext) throw new NoSuchElementException("No more pages to crawl")

      val (fetchUrl, canonicalUrl, depth) = queue.dequeue()
      pageCount += 1

      val result = fetchAndProcess(fetchUrl, canonicalUrl, depth)
      isFirstRequest = false
      result
    }

    /**
     * Fetch a page and process it.
     */
    private def fetchAndProcess(fetchUrl: String, canonicalUrl: String, depth: Int): LoadResult = {
      val robotsRules = if (config.respectRobotsTxt) {
        Some(RobotsTxtParser.getRules(fetchUrl, config.userAgent, config.timeout, config.urlPolicy))
      } else {
        None
      }

      if (robotsRules.exists(rules => !rules.isAllowed(extractPath(fetchUrl)))) {
        return LoadResult.skipped(fetchUrl, "Blocked by robots.txt")
      }

      WebCrawlerLoader
        .delayBeforeFetch(config.delay, robotsRules.flatMap(_.crawlDelay), isFirstRequest)
        .foreach(delay => Thread.sleep(delay.toMillis))

      // Fetch the page
      fetchPage(fetchUrl) match {
        case Left(error) =>
          // Deliberately no documentId: the links on this page were never followed, so the
          // pages below it are missing from this crawl too. Leaving it unnamed makes RAG.sync
          // skip its deletion pass, rather than delete those pages as gone.
          LoadResult.failure(fetchUrl, error)

        case Right((html, contentType, headers)) =>
          // Check content type
          if (!isAcceptableContentType(contentType)) {
            return LoadResult.skipped(fetchUrl, s"Content-Type not accepted: $contentType")
          }

          // Extract content and links
          val extraction = HtmlContentExtractor.extract(html, fetchUrl)

          // Queue new links if not at max depth
          if (depth < config.maxDepth) {
            queueDiscoveredLinks(extraction.links, depth + 1)
          }

          // Build document
          val version = DocumentVersion.fromContent(extraction.content)
          val doc = Document(
            id = canonicalUrl,
            content = extraction.content,
            metadata = metadata ++ Map(
              "source"      -> "web-crawler",
              "url"         -> fetchUrl,
              "title"       -> extraction.title,
              "crawl_depth" -> depth.toString
            ) ++ extraction.description.map("description" -> _).toMap
              ++ headers.get("ETag").map("etag" -> _).toMap
              ++ headers.get("Last-Modified").map("lastModified" -> _).toMap,
            hints = Some(DocumentHints.prose),
            version = Some(version)
          )

          if (doc.isEmpty) {
            LoadResult.skipped(fetchUrl, "Empty content after extraction")
          } else {
            LoadResult.success(doc)
          }
      }
    }

    /**
     * Fetch a page via HTTP. The URL and every redirect hop are checked by `config.urlPolicy`, and the request goes
     * only to an address it approved.
     */
    private def fetchPage(url: String): Either[NetworkError, (String, String, Map[String, String])] =
      PinnedHttpClient
        .get(
          url,
          Seq("User-Agent" -> config.userAgent, "Accept" -> "text/html,application/xhtml+xml,*/*;q=0.8"),
          config.timeout,
          config.urlPolicy
        )
        .flatMap { resp =>
          if (resp.status != 200) Left(NetworkError(s"HTTP ${resp.status}: ${resp.reason}", None, "http"))
          else {
            val headers = Map(
              "ETag"          -> resp.header("ETag"),
              "Last-Modified" -> resp.header("Last-Modified")
            ).collect { case (k, Some(v)) => k -> v }
            Right((resp.body, resp.header("Content-Type").getOrElse("text/html"), headers))
          }
        }

    /**
     * Queue discovered links for crawling.
     */
    private def queueDiscoveredLinks(links: Seq[String], depth: Int): Unit =
      // Check queue size limit
      if (queue.size >= config.maxQueueSize) ()
      else {
        val linksToProcess = links.iterator
        var continue       = true

        while (continue && linksToProcess.hasNext) {
          val link       = linksToProcess.next()
          val normalized = UrlNormalizer.normalize(link, config.includeQueryParams)

          // Skip if already visited, otherwise check if URL should be crawled
          if (!visited.contains(normalized) && shouldCrawl(normalized)) {
            visited += normalized
            queue.enqueue((link, normalized, depth))

            // Stop if queue is full
            if (queue.size >= config.maxQueueSize) {
              continue = false
            }
          }
        }
      }

    /**
     * Check if a URL should be crawled based on configuration.
     */
    private def shouldCrawl(url: String): Boolean = {
      // Must be valid HTTP URL
      if (!UrlNormalizer.isValidHttpUrl(url)) return false

      // Check same-domain restriction
      if (config.sameDomainOnly && !UrlNormalizer.isInDomains(url, seedDomains)) {
        return false
      }

      // Check follow patterns
      val matchesFollow = config.followPatterns.isEmpty ||
        config.followPatterns.contains("*") ||
        GlobPatternMatcher.matchesAny(url, config.followPatterns)

      // Check exclude patterns
      val matchesExclude = GlobPatternMatcher.matchesAny(url, config.excludePatterns)

      matchesFollow && !matchesExclude
    }

    /**
     * Check if content type is acceptable.
     */
    private def isAcceptableContentType(contentType: String): Boolean = {
      val ct = contentType.toLowerCase.split(";").head.trim
      config.acceptContentTypes.exists(accepted => ct.contains(accepted) || accepted.contains(ct))
    }

    private def extractPath(url: String): String = {
      val path = Try(new URI(url)).toOption.flatMap(uri => Option(uri.getRawPath)).getOrElse("")
      if (path.trim.isEmpty) "/" else path
    }
  }
}

object WebCrawlerLoader {

  /**
   * Create a crawler with a single seed URL.
   */
  def apply(url: String): WebCrawlerLoader =
    WebCrawlerLoader(Seq(url))

  /**
   * Create a crawler with multiple seed URLs.
   */
  def apply(urls: String*): WebCrawlerLoader =
    WebCrawlerLoader(urls.toSeq)

  /**
   * Create a crawler for documentation sites.
   * Uses polite configuration with longer delays.
   */
  def forDocs(url: String): WebCrawlerLoader =
    WebCrawlerLoader(Seq(url), CrawlerConfig.polite)

  /**
   * Create a single-page loader (no link following).
   * Useful when you just want to convert a specific page.
   */
  def singlePage(url: String): WebCrawlerLoader =
    WebCrawlerLoader(Seq(url), CrawlerConfig.singlePage)

  /**
   * How long to wait before the next fetch, given the configured delay and any
   * robots.txt crawl-delay. None means fetch immediately.
   */
  private[loader] def delayBeforeFetch(
    configDelay: FiniteDuration,
    robotsCrawlDelay: Option[FiniteDuration],
    isFirstRequest: Boolean
  ): Option[FiniteDuration] = {
    val effectiveDelay = robotsCrawlDelay.fold(configDelay)(_.max(configDelay))
    if (!isFirstRequest && effectiveDelay > Duration.Zero) Some(effectiveDelay) else None
  }
}
