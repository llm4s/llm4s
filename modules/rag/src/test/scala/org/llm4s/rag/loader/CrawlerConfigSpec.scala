package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CrawlerConfigSpec extends AnyFlatSpec with Matchers {

  "CrawlerConfig" should "have sensible defaults" in {
    val config = CrawlerConfig()

    config.maxDepth shouldBe 3
    config.maxPages shouldBe 1000
    config.delayMs shouldBe 500
    config.timeoutMs shouldBe 30000
    config.respectRobotsTxt shouldBe true
    config.sameDomainOnly shouldBe true
    config.includeQueryParams shouldBe false
    config.followPatterns shouldBe Seq("*")
    config.excludePatterns shouldBe empty
  }

  it should "support fluent configuration" in {
    val config = CrawlerConfig()
      .withMaxDepth(5)
      .withMaxPages(500)
      .withDelay(1000)
      .withTimeout(60000)
      .withRobotsTxt(false)
      .withSameDomainOnly(false)
      .withQueryParams(true)
      .withFollowPatterns("example.com/*")
      .withExcludePatterns("example.com/archive/*")
      .withUserAgent("CustomBot/1.0")

    config.maxDepth shouldBe 5
    config.maxPages shouldBe 500
    config.delayMs shouldBe 1000
    config.timeoutMs shouldBe 60000
    config.respectRobotsTxt shouldBe false
    config.sameDomainOnly shouldBe false
    config.includeQueryParams shouldBe true
    config.followPatterns shouldBe Seq("example.com/*")
    config.excludePatterns shouldBe Seq("example.com/archive/*")
    config.userAgent shouldBe "CustomBot/1.0"
  }

  "CrawlerConfig.polite" should "have conservative settings" in {
    val config = CrawlerConfig.polite

    config.maxDepth shouldBe 2
    config.maxPages shouldBe 100
    config.delayMs shouldBe 1000
    config.respectRobotsTxt shouldBe true
  }

  "CrawlerConfig.fast" should "have aggressive settings" in {
    val config = CrawlerConfig.fast

    config.maxDepth shouldBe 5
    config.maxPages shouldBe 5000
    config.delayMs shouldBe 100
    config.respectRobotsTxt shouldBe true
  }

  "CrawlerConfig.singlePage" should "not follow links" in {
    val config = CrawlerConfig.singlePage

    config.maxDepth shouldBe 0
    config.maxPages shouldBe 1
  }

  "CrawlerConfig" should "accept content types configuration" in {
    val config = CrawlerConfig()
      .withContentTypes(Set("text/html", "text/plain"))

    config.acceptContentTypes should contain("text/html")
    config.acceptContentTypes should contain("text/plain")
    config.acceptContentTypes should not contain "application/json"
  }

  it should "accept max queue size configuration" in {
    val config = CrawlerConfig().withMaxQueueSize(5000)
    config.maxQueueSize shouldBe 5000
  }
}
