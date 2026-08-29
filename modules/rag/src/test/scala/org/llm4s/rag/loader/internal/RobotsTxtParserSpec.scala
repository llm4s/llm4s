package org.llm4s.rag.loader.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class RobotsTxtParserSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit =
    RobotsTxtParser.clearCache()

  "RobotsTxtParser.parse" should "parse simple disallow rules" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Disallow: /admin/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/private/")
    robots.disallowRules should contain("/admin/")
    robots.isAllowed("/public/page") shouldBe true
    robots.isAllowed("/private/data") shouldBe false
    robots.isAllowed("/admin/users") shouldBe false
  }

  it should "parse allow rules" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Allow: /private/public/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/private/secret") shouldBe false
    robots.isAllowed("/private/public/page") shouldBe true
  }

  it should "prefer longer matching rules" in {
    val content =
      """User-agent: *
        |Disallow: /a
        |Allow: /a/b
        |Disallow: /a/b/c
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/a/page") shouldBe false
    robots.isAllowed("/a/b/page") shouldBe true
    robots.isAllowed("/a/b/c/page") shouldBe false
  }

  it should "parse crawl-delay" in {
    val content =
      """User-agent: *
        |Crawl-delay: 10
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")
    robots.crawlDelay shouldBe Some(10)
  }

  it should "use specific user-agent rules over wildcard" in {
    val content =
      """User-agent: *
        |Disallow: /all/
        |
        |User-agent: LLM4S-Crawler
        |Disallow: /specific/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/specific/")
    robots.disallowRules should not contain "/all/"
  }

  it should "support multiple user-agent lines in a group" in {
    val content =
      """User-agent: BotA
        |User-agent: LLM4S-Crawler
        |Disallow: /private/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/private/data") shouldBe false
  }

  it should "not fall back to wildcard when specific group has no rules" in {
    val content =
      """User-agent: *
        |Disallow: /all/
        |
        |User-agent: LLM4S-Crawler
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules shouldBe empty
    robots.isAllowed("/all/blocked") shouldBe true
  }

  it should "fall back to wildcard when no specific match" in {
    val content =
      """User-agent: *
        |Disallow: /general/
        |
        |User-agent: OtherBot
        |Disallow: /other/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/general/")
    robots.disallowRules should not contain "/other/"
  }

  it should "treat regex metacharacters as literals" in {
    val content =
      """User-agent: *
        |Disallow: /private.html
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/privateXhtml") shouldBe true
    robots.isAllowed("/private.html") shouldBe false
  }

  it should "ignore comments" in {
    val content =
      """# This is a comment
        |User-agent: * # inline comment
        |Disallow: /private/ # block private
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")
    robots.disallowRules should contain("/private/")
  }

  it should "handle empty content" in {
    val robots = RobotsTxtParser.parse("", "LLM4S-Crawler/1.0")
    robots.allowRules shouldBe empty
    robots.disallowRules shouldBe empty
    robots.isAllowed("/any/path") shouldBe true
  }

  "RobotsTxt.isAllowed" should "allow all paths by default" in {
    val robots = RobotsTxtParser.RobotsTxt.empty
    robots.isAllowed("/any/path") shouldBe true
    robots.isAllowed("/") shouldBe true
  }

  it should "handle root disallow" in {
    val robots = RobotsTxtParser.RobotsTxt(disallowRules = Seq("/"))
    robots.isAllowed("/any/path") shouldBe false
    robots.isAllowed("/") shouldBe false
  }

  it should "allow ties go to allow rules" in {
    val robots = RobotsTxtParser.RobotsTxt(
      allowRules = Seq("/path"),
      disallowRules = Seq("/path")
    )
    robots.isAllowed("/path") shouldBe true
  }

  it should "handle empty disallow rule" in {
    val robots = RobotsTxtParser.RobotsTxt(disallowRules = Seq(""))
    robots.isAllowed("/any/path") shouldBe false // Empty rule matches everything
  }

  // ==========================================================================
  // Wildcard pattern tests
  // ==========================================================================

  "RobotsTxtParser wildcard patterns" should "support * wildcard" in {
    val content =
      """User-agent: *
        |Disallow: /admin/*.php
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")

    robots.isAllowed("/admin/config.php") shouldBe false
    robots.isAllowed("/admin/users.php") shouldBe false
    robots.isAllowed("/admin/config.html") shouldBe true
    robots.isAllowed("/public/test.php") shouldBe true
  }

  it should "support $ anchor for exact end matching" in {
    val content =
      """User-agent: *
        |Disallow: /*.gif$
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")

    robots.isAllowed("/image.gif") shouldBe false
    robots.isAllowed("/path/image.gif") shouldBe false
    robots.isAllowed("/image.gif.bak") shouldBe true // Not exact end
    robots.isAllowed("/image.png") shouldBe true
  }

  it should "support * and $ together" in {
    val content =
      """User-agent: *
        |Disallow: /secret*.html$
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")

    robots.isAllowed("/secret.html") shouldBe false
    robots.isAllowed("/secret-file.html") shouldBe false
    robots.isAllowed("/secret-file.html.bak") shouldBe true
  }

  // ==========================================================================
  // RobotsTxt.empty tests
  // ==========================================================================

  "RobotsTxt.empty" should "have no rules" in {
    val emptyRobots = RobotsTxtParser.RobotsTxt.empty
    emptyRobots.allowRules shouldBe Seq.empty
    emptyRobots.disallowRules shouldBe Seq.empty
    emptyRobots.crawlDelay shouldBe None
  }

  // ==========================================================================
  // Parse edge cases
  // ==========================================================================

  "RobotsTxtParser.parse edge cases" should "handle crawl-delay with decimal value" in {
    val content =
      """User-agent: *
        |Crawl-delay: 2.5
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    robots.crawlDelay shouldBe Some(2) // Truncated to int
  }

  it should "handle invalid crawl-delay gracefully" in {
    val content =
      """User-agent: *
        |Crawl-delay: invalid
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    robots.crawlDelay shouldBe None
  }

  it should "handle multiple groups separated by blank lines" in {
    val content =
      """User-agent: BotA
        |Disallow: /botA/
        |
        |User-agent: BotB
        |Disallow: /botB/
        |
        |User-agent: *
        |Disallow: /all/
        |""".stripMargin

    val robotsA = RobotsTxtParser.parse(content, "BotA/1.0")
    val robotsB = RobotsTxtParser.parse(content, "BotB/1.0")
    val robotsC = RobotsTxtParser.parse(content, "BotC/1.0")

    robotsA.disallowRules should contain("/botA/")
    robotsB.disallowRules should contain("/botB/")
    robotsC.disallowRules should contain("/all/")
  }

  it should "handle empty value for user-agent" in {
    val content =
      """User-agent:
        |Disallow: /test/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    // Empty user-agent should be ignored
    robots.disallowRules shouldBe empty
  }

  it should "handle empty value for allow/disallow" in {
    val content =
      """User-agent: *
        |Disallow:
        |Allow:
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    robots.disallowRules shouldBe empty
    robots.allowRules shouldBe empty
  }

  it should "handle unknown directives gracefully" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Sitemap: http://example.com/sitemap.xml
        |Unknown-directive: value
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    robots.disallowRules should contain("/private/")
    robots.isAllowed("/private/page") shouldBe false
  }

  it should "handle malformed lines" in {
    val content =
      """User-agent: *
        |This is not a directive
        |Disallow: /private/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "TestBot")
    robots.disallowRules should contain("/private/")
  }

  it should "handle user-agent followed by directive then more user-agents" in {
    val content =
      """User-agent: BotA
        |Disallow: /botA/
        |User-agent: BotB
        |Disallow: /botB/
        |""".stripMargin

    // After sawDirective, new user-agent should flush the group
    val robotsA = RobotsTxtParser.parse(content, "BotA/1.0")
    val robotsB = RobotsTxtParser.parse(content, "BotB/1.0")

    robotsA.disallowRules should contain("/botA/")
    robotsA.disallowRules should not contain "/botB/"
    robotsB.disallowRules should contain("/botB/")
  }

  // ==========================================================================
  // Caching tests
  // ==========================================================================

  "RobotsTxtParser.clearCache" should "clear the cache" in {
    // This is mainly for coverage - we can't easily verify cache state
    // but we can verify the method doesn't throw
    RobotsTxtParser.clearCache()
    // Success if no exception
    succeed
  }

  // ==========================================================================
  // getRules tests
  // ==========================================================================

  "RobotsTxtParser.getRules" should "return empty rules for invalid URL" in {
    val rules = RobotsTxtParser.getRules("not-a-valid-url", "TestBot")
    rules.allowRules shouldBe empty
    rules.disallowRules shouldBe empty
    rules.crawlDelay shouldBe None
  }

  it should "return empty rules for URL with no host" in {
    val rules = RobotsTxtParser.getRules("file:///path/to/file", "TestBot")
    // File URLs have no host
    rules.allowRules shouldBe empty
    rules.disallowRules shouldBe empty
  }

  // ==========================================================================
  // isAllowed static method tests
  // ==========================================================================

  "RobotsTxtParser.isAllowed" should "return true for invalid URL" in {
    val allowed = RobotsTxtParser.isAllowed("not-a-valid-url", "TestBot")
    allowed shouldBe true // Allow on error
  }

  it should "return true for URL with no host" in {
    val allowed = RobotsTxtParser.isAllowed("file:///path/to/file", "TestBot")
    allowed shouldBe true // Allow on error
  }

  // ==========================================================================
  // Rule selection tests
  // ==========================================================================

  "RobotsTxtParser rule selection" should "prefer exact user-agent match over prefix" in {
    val content =
      """User-agent: LLM4S
        |Disallow: /exact/
        |
        |User-agent: LLM4S-Crawler
        |Disallow: /prefix/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")
    // "LLM4S-Crawler" is a longer prefix match than "LLM4S"
    robots.disallowRules should contain("/prefix/")
    robots.disallowRules should not contain "/exact/"
  }

  it should "select wildcard when no other match" in {
    val content =
      """User-agent: OtherBot
        |Disallow: /other/
        |
        |User-agent: *
        |Disallow: /wildcard/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "UnknownBot/1.0")
    robots.disallowRules should contain("/wildcard/")
    robots.disallowRules should not contain "/other/"
  }

  it should "return empty when no matching groups" in {
    val content =
      """User-agent: OtherBot
        |Disallow: /other/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "UnknownBot/1.0")
    robots.disallowRules shouldBe empty
    robots.allowRules shouldBe empty
  }
}
