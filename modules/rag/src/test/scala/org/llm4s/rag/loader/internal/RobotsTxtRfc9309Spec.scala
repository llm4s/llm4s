package org.llm4s.rag.loader.internal

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global

/** robots.txt is parsed and matched as RFC 9309 says (#2999). */
class RobotsTxtRfc9309Spec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = RobotsTxtParser.clearCache()

  private val agent = "LLM4S-Crawler/1.0"

  private def rules(content: String): RobotsTxtParser.RobotsTxt = RobotsTxtParser.parse(content, agent)

  // ========== Groups: blank and comment lines ==========

  "RobotsTxtParser.parse" should "keep a group's rules after a comment line inside it" in {
    val robots = rules("User-agent: *\n# admin area\nDisallow: /admin\n")
    robots.disallowRules shouldBe Seq("/admin")
    robots.isAllowed("/admin") shouldBe false
  }

  it should "keep a group's rules after a blank line between User-agent and a rule" in {
    val robots = rules("User-agent: *\n\nDisallow: /admin\n")
    robots.isAllowed("/admin") shouldBe false
  }

  it should "keep every rule when blank and comment lines separate them" in {
    val robots = rules("User-agent: *\nDisallow: /a\n\n# more\nDisallow: /b\n   \nAllow: /b/open\nCrawl-delay: 3\n")
    robots.disallowRules shouldBe Seq("/a", "/b")
    robots.allowRules shouldBe Seq("/b/open")
    robots.crawlDelay shouldBe Some(3.seconds)
  }

  it should "keep User-agent lines separated by a blank or comment line in one group" in {
    val content = "User-agent: OtherBot\n\n# and us\nUser-agent: LLM4S-Crawler\nDisallow: /shared\n"
    rules(content).isAllowed("/shared") shouldBe false
    RobotsTxtParser.parse(content, "OtherBot").isAllowed("/shared") shouldBe false
  }

  it should "start a new group only at a User-agent line that follows a rule" in {
    val content = "User-agent: OtherBot\nDisallow: /other\n\nUser-agent: *\nDisallow: /all\n"
    rules(content).disallowRules shouldBe Seq("/all")
    RobotsTxtParser.parse(content, "OtherBot").disallowRules shouldBe Seq("/other")
  }

  it should "merge the groups that name the same user agent" in {
    val content = "User-agent: LLM4S-Crawler\nDisallow: /one\n\nUser-agent: *\nDisallow: /all\n\n" +
      "User-agent: llm4s-crawler\nDisallow: /two\nCrawl-delay: 4\n"
    val robots = rules(content)
    robots.disallowRules shouldBe Seq("/one", "/two")
    robots.crawlDelay shouldBe Some(4.seconds)
  }

  it should "merge the wildcard groups" in {
    val robots =
      rules("User-agent: *\nDisallow: /one\n\nUser-agent: Other\nDisallow: /x\n\nUser-agent: *\nDisallow: /two\n")
    robots.disallowRules shouldBe Seq("/one", "/two")
  }

  it should "ignore rules before the first User-agent line" in {
    rules("Disallow: /orphan\nUser-agent: *\nDisallow: /kept\n").disallowRules shouldBe Seq("/kept")
  }

  // ========== Matching: path and query ==========

  "RobotsTxt.isAllowed" should "match a rule against the query as well as the path" in {
    val robots = rules("User-agent: *\nDisallow: /*?\nDisallow: /search?q=\n")
    robots.isAllowed("/page") shouldBe true
    robots.isAllowed("/page?session=1") shouldBe false
    robots.isAllowed("/search?q=scala") shouldBe false
  }

  it should "match a query-only rule exactly where it is anchored" in {
    val robots = rules("User-agent: *\nDisallow: /list?page=1$\n")
    robots.isAllowed("/list?page=1") shouldBe false
    robots.isAllowed("/list?page=10") shouldBe true
  }

  it should "compare percent-encodings case-insensitively, and unreserved characters decoded" in {
    val robots = rules("User-agent: *\nDisallow: /a%3cb\nDisallow: /%7Euser\n")
    robots.isAllowed("/a%3Cb") shouldBe false
    robots.isAllowed("/~user/page") shouldBe false
  }

  it should "percent-encode a rule's non-ASCII characters before matching" in {
    val robots = rules("User-agent: *\nDisallow: /café\n")
    robots.isAllowed("/caf%C3%A9/menu") shouldBe false
    robots.isAllowed("/cafe") shouldBe true
  }

  it should "match a rule with many wildcards in linear time, without backtracking" in {
    val rule   = "/" + ("*a" * 40) + "*b"
    val robots = rules(s"User-agent: *\nDisallow: $rule\n")
    val path   = "/" + ("a" * 20000)
    val result = Future(robots.isAllowed(path))
    Await.result(result, 10.seconds) shouldBe true
    robots.isAllowed(path + "b") shouldBe false
  }

  // ========== Concurrency and bounds ==========

  "RobotsTxtParser and GlobPatternMatcher" should "keep their caches bounded and usable from many threads" in {
    val work = (1 to 8).map { t =>
      Future {
        (1 to 2000).foreach { i =>
          GlobPatternMatcher.matches(s"https://example.com/$t/$i", s"https://example.com/$t/*")
          GlobPatternMatcher.matches(s"https://example.com/$t/$i", s"https://example.com/$t/$i-*")
        }
      }
    }
    Await.result(Future.sequence(work), 60.seconds)
    GlobPatternMatcher.cacheSize should be <= GlobPatternMatcher.MaxCachedPatterns
  }
}
