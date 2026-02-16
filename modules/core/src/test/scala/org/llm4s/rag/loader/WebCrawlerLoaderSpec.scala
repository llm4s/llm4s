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
}
