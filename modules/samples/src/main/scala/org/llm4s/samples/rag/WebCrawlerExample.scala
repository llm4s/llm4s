package org.llm4s.samples.rag

import org.llm4s.rag.loader._
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating the WebCrawlerLoader for crawling web content into RAG.
 *
 * Shows how to:
 * - Crawl documentation sites with breadth-first discovery
 * - Configure crawl depth, page limits, and patterns
 * - Respect robots.txt and rate limiting
 * - Combine web content with other document sources
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.WebCrawlerExample"
 * }}}
 *
 * NOTE: This example shows the API but doesn't perform actual crawling
 * unless you uncomment the crawling sections.
 */
object WebCrawlerExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Web Crawler Loader Example ===")

  // ========== 1. Basic WebCrawlerLoader Creation ==========
  logger.info("1. Creating a basic WebCrawlerLoader...")

  val basicCrawler = WebCrawlerLoader("https://docs.example.com")
    .tap(c => logger.info("   Description: {}", c.description))
    .tap(c => logger.info("   Seed URLs: {}", c.seedUrls))
    .tap(c => logger.info("   Max depth: {}", c.config.maxDepth))
    .tap(c => logger.info("   Max pages: {}", c.config.maxPages))

  // ========== 2. Configuring Crawl Behavior ==========
  logger.info("2. Configuring crawl behavior...")

  val configuredCrawler = WebCrawlerLoader("https://docs.example.com")
    .withMaxDepth(3)    // Follow links up to 3 levels deep
    .withMaxPages(500)  // Crawl at most 500 pages
    .withDelay(1000)    // Wait 1 second between requests
    .withTimeout(30000) // 30 second timeout per request
    .tap(c => logger.info("   Max depth: {}", c.config.maxDepth))
    .tap(c => logger.info("   Max pages: {}", c.config.maxPages))
    .tap(c => logger.info("   Delay: {}ms", c.config.delayMs))

  // ========== 3. URL Pattern Filtering ==========
  logger.info("3. Using URL patterns to control scope...")

  val patternCrawler = WebCrawlerLoader("https://docs.example.com")
    .withFollowPatterns(
      "https://docs.example.com/guide/**", // Crawl guide section
      "https://docs.example.com/api/**"    // Crawl API section
    )
    .withExcludePatterns(
      "https://docs.example.com/*/archive/**",  // Skip archive pages
      "https://docs.example.com/*/changelog/**" // Skip changelogs
    )
    .tap(c => logger.info("   Follow patterns: {}", c.config.followPatterns.mkString(", ")))
    .tap(c => logger.info("   Exclude patterns: {}", c.config.excludePatterns.mkString(", ")))

  // ========== 4. Domain Restrictions ==========
  logger.info("4. Domain restrictions...")

  // By default, crawling is restricted to the seed URL domain
  val sameDomainCrawler = WebCrawlerLoader("https://docs.example.com")
    .tap(c => logger.info("   Same domain only: {} (default)", c.config.sameDomainOnly))

  // Can disable for cross-domain crawling (use with caution)
  val crossDomainCrawler = WebCrawlerLoader("https://docs.example.com")
    .withSameDomainOnly(false)
    .tap(c => logger.info("   Cross-domain enabled: {}", !c.config.sameDomainOnly))

  // ========== 5. robots.txt Handling ==========
  logger.info("5. robots.txt respect...")

  val politeBot = WebCrawlerLoader("https://example.com")
    .withRobotsTxt(true) // Default - respects robots.txt
    .tap(c => logger.info("   Respects robots.txt: {}", c.config.respectRobotsTxt))

  // Can disable for testing (not recommended for production)
  val testBot = WebCrawlerLoader("https://example.com")
    .withRobotsTxt(false)
    .tap(c => logger.info("   Ignores robots.txt: {}", !c.config.respectRobotsTxt))

  // ========== 6. Preset Configurations ==========
  logger.info("6. Using preset configurations...")

  // Polite crawler for documentation sites
  val politeLoader = WebCrawlerLoader
    .forDocs("https://docs.example.com")
    .tap(l =>
      logger.info("   forDocs: depth={}, pages={}, delay={}ms", l.config.maxDepth, l.config.maxPages, l.config.delayMs)
    )

  // Single page only (no link following)
  val singlePageLoader = WebCrawlerLoader
    .singlePage("https://example.com/specific-page")
    .tap(l => logger.info("   singlePage: depth={}, pages={}", l.config.maxDepth, l.config.maxPages))

  // ========== 7. Adding Metadata ==========
  logger.info("7. Adding metadata to crawled documents...")

  val metadataCrawler = WebCrawlerLoader("https://docs.example.com")
    .withMetadata(
      Map(
        "source"     -> "documentation",
        "category"   -> "api-reference",
        "crawl_date" -> java.time.LocalDate.now.toString
      )
    )
    .tap(c => logger.info("   Metadata: {}", c.metadata))

  // ========== 8. Multiple Seed URLs ==========
  logger.info("8. Crawling from multiple seed URLs...")

  val multiSeedCrawler = WebCrawlerLoader(
    "https://docs.example.com/getting-started",
    "https://docs.example.com/tutorials",
    "https://docs.example.com/api"
  )
    .withSeed("https://docs.example.com/faq") // Add another seed
    .tap(c => logger.info("   Seed URLs: {}", c.seedUrls.size))
    .tap(c => c.seedUrls.foreach(url => logger.info("     - {}", url)))

  // ========== 9. Combining with Other Loaders ==========
  logger.info("9. Combining with other document loaders...")

  val webDocs = WebCrawlerLoader("https://docs.example.com")
    .withMaxPages(100)

  val localDocs = TextLoader
    .builder()
    .add("local-1", "Local documentation content")
    .add("local-2", "Additional local notes")
    .build()

  val combined = (webDocs ++ localDocs)
    .tap(c => logger.info("   Combined loader: {}", c.description))

  // ========== 10. Query Parameter Handling ==========
  logger.info("10. Query parameter handling...")

  // By default, query params are stripped (deduplication)
  val defaultParams = WebCrawlerLoader("https://example.com")
    .tap(c => logger.info("   Include query params: {} (default)", c.config.includeQueryParams))
  logger.info("   URLs like /page?id=1 and /page?id=2 treated as same page")

  // Can include query params for dynamic sites
  val withParams = WebCrawlerLoader("https://example.com")
    .withQueryParams(true)
    .tap(c => logger.info("   Include query params: {}", c.config.includeQueryParams))
  logger.info("   URLs like /page?id=1 and /page?id=2 treated as different pages")

  // ========== 11. Example RAG Integration ==========
  logger.info("11. RAG integration example (code only, not executed)...")

  logger.info("    // Build-time crawl integration:")
  logger.info("    val rag = RAG.builder()")
  logger.info("      .withEmbeddings(EmbeddingProvider.OpenAI)")
  logger.info("      .withDocuments(WebCrawlerLoader.forDocs(\"https://docs.example.com\"))")
  logger.info("      .build()")
  logger.info("      .toOption.get")
  logger.info("")
  logger.info("    // Runtime crawl integration:")
  logger.info("    val stats = rag.ingest(")
  logger.info("      WebCrawlerLoader(\"https://newdocs.example.com\")")
  logger.info("        .withMaxPages(200)")
  logger.info("    )")
  logger.info("    logger.info(\"Crawled {} pages\", stats.map(_.successful).getOrElse(0))")

  // ========== 12. Content Types ==========
  logger.info("12. Content type filtering...")

  val htmlOnlyCrawler = WebCrawlerLoader("https://example.com")
    .tap(c => logger.info("   Accepted types: {}", c.config.acceptContentTypes.mkString(", ")))
  logger.info("   Non-HTML content (PDFs, images, etc.) is automatically skipped")

  // ========== 13. Memory Management ==========
  logger.info("13. Memory management...")

  val safeCrawler = WebCrawlerLoader("https://example.com")
    .withMaxQueueSize(10000) // Limit URL queue size
    .withMaxPages(1000)      // Limit total pages
    .tap(c => logger.info("   Max queue size: {}", c.config.maxQueueSize))

  logger.info("   Prevents unbounded memory usage during large crawls")

  logger.info("=== Example Complete ===")
  logger.info("To perform actual crawling, configure environment variables and uncomment")
  logger.info("the RAG integration code above.")
}
