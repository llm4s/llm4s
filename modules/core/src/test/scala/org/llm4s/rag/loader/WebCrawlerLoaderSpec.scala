package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.rag.loader.internal.{ GlobPatternMatcher, RobotsTxtParser }

class WebCrawlerLoaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = {
    GlobPatternMatcher.clearCache()
    RobotsTxtParser.clearCache()
  }

  // ==========================================================================
  // Creation
  // ==========================================================================

  "WebCrawlerLoader" should "be created with a single seed URL" in {
    val loader = WebCrawlerLoader("http://example.com")

    loader.seedUrls shouldBe Seq("http://example.com")
    loader.description should include("1 seeds")
  }

  it should "be created with multiple seed URLs" in {
    val loader = WebCrawlerLoader("http://a.com", "http://b.com", "http://c.com")

    loader.seedUrls should have size 3
    loader.description should include("3 seeds")
  }

  // ==========================================================================
  // Fluent Configuration
  // ==========================================================================

  it should "support fluent configuration" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withMaxDepth(5)
      .withMaxPages(500)
      .withDelay(1000)
      .withRobotsTxt(false)
      .withSameDomainOnly(false)
      .withFollowPatterns("example.com/docs/*")
      .withExcludePatterns("example.com/archive/*")

    loader.config.maxDepth shouldBe 5
    loader.config.maxPages shouldBe 500
    loader.config.delayMs shouldBe 1000
    loader.config.respectRobotsTxt shouldBe false
    loader.config.sameDomainOnly shouldBe false
    loader.config.followPatterns shouldBe Seq("example.com/docs/*")
    loader.config.excludePatterns shouldBe Seq("example.com/archive/*")
  }

  it should "add metadata to documents" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withMetadata(Map("source" -> "test", "category" -> "docs"))

    loader.metadata shouldBe Map("source" -> "test", "category" -> "docs")
  }

  it should "add seed URLs with withSeed" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withSeed("http://other.com")
      .withSeeds("http://a.com", "http://b.com")

    loader.seedUrls should have size 4
  }

  it should "set timeout" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withTimeout(5000)

    loader.config.timeoutMs shouldBe 5000
  }

  it should "set query params inclusion" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withQueryParams(true)

    loader.config.includeQueryParams shouldBe true
  }

  it should "set max queue size" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withMaxQueueSize(500)

    loader.config.maxQueueSize shouldBe 500
  }

  // ==========================================================================
  // estimatedCount and description
  // ==========================================================================

  it should "report estimatedCount as None" in {
    val loader = WebCrawlerLoader("http://example.com")
    loader.estimatedCount shouldBe None
  }

  it should "have a descriptive description" in {
    val loader = WebCrawlerLoader("http://example.com")
      .withMaxDepth(5)

    loader.description should include("WebCrawlerLoader")
    loader.description should include("1 seeds")
    loader.description should include("depth=5")
  }

  // ==========================================================================
  // Factory Methods
  // ==========================================================================

  "WebCrawlerLoader.forDocs" should "use polite configuration" in {
    val loader = WebCrawlerLoader.forDocs("http://docs.example.com")

    loader.config.maxDepth shouldBe 2
    loader.config.maxPages shouldBe 100
    loader.config.delayMs shouldBe 1000
    loader.config.respectRobotsTxt shouldBe true
  }

  "WebCrawlerLoader.singlePage" should "not follow links" in {
    val loader = WebCrawlerLoader.singlePage("http://example.com/page")

    loader.config.maxDepth shouldBe 0
    loader.config.maxPages shouldBe 1
  }

  // ==========================================================================
  // Combinators
  // ==========================================================================

  "WebCrawlerLoader" should "be combinable with other loaders" in {
    val crawlerLoader = WebCrawlerLoader("http://example.com")
    val textLoader    = TextLoader("doc content", "doc-1")

    val combined = crawlerLoader ++ textLoader

    combined shouldBe a[DocumentLoader]
    combined.description should include("Combined")
  }

  it should "support DocumentLoader trait methods" in {
    val loader: DocumentLoader = WebCrawlerLoader("http://example.com")

    loader.estimatedCount shouldBe None
    loader.description should include("WebCrawlerLoader")
  }

  // ==========================================================================
  // SSRF Protection
  // ==========================================================================

  it should "reject blocked URLs before making requests" in {
    val config = CrawlerConfig.singlePage.withRobotsTxt(false)
    val loader = WebCrawlerLoader(Seq("http://169.254.169.254/latest/meta-data/"), config)

    val result = loader.load().next()
    result shouldBe a[LoadResult.Failure]

    result match {
      case LoadResult.Failure(_, error, _) =>
        error.message should include("blocked range")
      case _ => fail("Expected a failure for blocked URL")
    }
  }

  // ==========================================================================
  // CrawlerConfig presets
  // ==========================================================================

  "CrawlerConfig.default" should "have standard defaults" in {
    val config = CrawlerConfig.default

    config.maxDepth shouldBe 3
    config.maxPages shouldBe 1000
    config.delayMs shouldBe 500
    config.respectRobotsTxt shouldBe true
    config.sameDomainOnly shouldBe true
    config.maxQueueSize shouldBe 10000
    config.includeQueryParams shouldBe false
  }

  "CrawlerConfig.fast" should "use aggressive settings" in {
    val config = CrawlerConfig.fast

    config.maxDepth shouldBe 5
    config.maxPages shouldBe 5000
    config.delayMs shouldBe 100
  }

  "CrawlerConfig" should "support fluent user agent configuration" in {
    val config = CrawlerConfig.default
      .withUserAgent("MyBot/2.0")

    config.userAgent shouldBe "MyBot/2.0"
  }

  it should "support fluent content types configuration" in {
    val config = CrawlerConfig.default
      .withContentTypes(Set("text/html"))

    config.acceptContentTypes shouldBe Set("text/html")
  }

  it should "produce an empty result iterator when no seed URLs are provided" in {
    val loader = WebCrawlerLoader(Seq.empty)

    loader.seedUrls shouldBe Seq.empty
    loader.load().toList shouldBe empty
  }
}
