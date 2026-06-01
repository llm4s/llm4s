package org.llm4s.rag.loader.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class GlobPatternMatcherSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit =
    GlobPatternMatcher.clearCache()

  "GlobPatternMatcher.matches" should "match literal strings" in {
    GlobPatternMatcher.matches("http://example.com/page", "http://example.com/page") shouldBe true
    GlobPatternMatcher.matches("http://example.com/page", "http://example.com/other") shouldBe false
  }

  it should "match single asterisk wildcard (non-path-crossing)" in {
    GlobPatternMatcher.matches("http://example.com/docs/page", "http://example.com/docs/*") shouldBe true
    GlobPatternMatcher.matches("http://example.com/docs/sub/page", "http://example.com/docs/*") shouldBe false
  }

  it should "match double asterisk wildcard (path-crossing)" in {
    GlobPatternMatcher.matches("http://example.com/docs/page", "http://example.com/docs/**") shouldBe true
    GlobPatternMatcher.matches("http://example.com/docs/sub/page", "http://example.com/docs/**") shouldBe true
    GlobPatternMatcher.matches("http://example.com/docs/a/b/c/page", "http://example.com/**") shouldBe true
  }

  it should "match question mark wildcard (single char)" in {
    GlobPatternMatcher.matches("http://example.com/page1", "http://example.com/page?") shouldBe true
    GlobPatternMatcher.matches("http://example.com/pageA", "http://example.com/page?") shouldBe true
    GlobPatternMatcher.matches("http://example.com/page12", "http://example.com/page?") shouldBe false
  }

  it should "escape regex special characters" in {
    GlobPatternMatcher.matches("http://example.com/path.html", "http://example.com/path.html") shouldBe true
    GlobPatternMatcher.matches("http://example.com/pathXhtml", "http://example.com/path.html") shouldBe false
  }

  it should "be case insensitive" in {
    GlobPatternMatcher.matches("http://EXAMPLE.COM/PAGE", "http://example.com/page") shouldBe true
    GlobPatternMatcher.matches("http://example.com/PAGE", "http://example.com/*") shouldBe true
  }

  it should "fallback to literal matching for overlong patterns" in {
    val longPattern = "A" * 1200
    GlobPatternMatcher.matches(longPattern, longPattern) shouldBe true
    GlobPatternMatcher.matches("different", longPattern) shouldBe false
  }

  "GlobPatternMatcher.matchesAny" should "return true if any pattern matches" in {
    val patterns = Seq("http://a.com/*", "http://b.com/*", "http://c.com/*")
    GlobPatternMatcher.matchesAny("http://b.com/page", patterns) shouldBe true
    GlobPatternMatcher.matchesAny("http://d.com/page", patterns) shouldBe false
  }

  it should "handle empty patterns list" in {
    GlobPatternMatcher.matchesAny("http://example.com/page", Seq.empty) shouldBe false
  }

  "GlobPatternMatcher.filter" should "filter URLs by include and exclude patterns" in {
    val urls = Seq(
      "http://example.com/docs/page1",
      "http://example.com/docs/page2",
      "http://example.com/api/endpoint",
      "http://example.com/docs/archive/old"
    )

    val includePatterns = Seq("http://example.com/docs/**")
    val excludePatterns = Seq("http://example.com/docs/archive/**")

    val filtered = GlobPatternMatcher.filter(urls, includePatterns, excludePatterns)

    filtered should contain("http://example.com/docs/page1")
    filtered should contain("http://example.com/docs/page2")
    filtered should not contain "http://example.com/api/endpoint"
    filtered should not contain "http://example.com/docs/archive/old"
  }

  it should "include all when include patterns is empty" in {
    val urls     = Seq("http://a.com/page", "http://b.com/page")
    val filtered = GlobPatternMatcher.filter(urls, Seq.empty, Seq.empty)
    filtered shouldBe urls
  }
}
