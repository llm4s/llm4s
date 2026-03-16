package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.loader.internal.GlobPatternMatcher

class GlobPatternMatcherSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // Basic matching
  // =========================================================================

  "GlobPatternMatcher.matches" should "match exact URL" in {
    GlobPatternMatcher.matches("http://example.com/page", "http://example.com/page") shouldBe true
  }

  it should "not match different URLs" in {
    GlobPatternMatcher.matches("http://example.com/page1", "http://example.com/page2") shouldBe false
  }

  // =========================================================================
  // Single asterisk (*) - matches within path segment
  // =========================================================================

  it should "match single asterisk within path segment" in {
    GlobPatternMatcher.matches("http://example.com/page1.html", "http://example.com/page*.html") shouldBe true
    GlobPatternMatcher.matches("http://example.com/page42.html", "http://example.com/page*.html") shouldBe true
  }

  it should "not match single asterisk across path separators" in {
    GlobPatternMatcher.matches(
      "http://example.com/a/b/c.html",
      "http://example.com/a*.html"
    ) shouldBe false
  }

  it should "match asterisk for any string within segment" in {
    GlobPatternMatcher.matches("http://example.com/docs/guide", "http://example.com/docs/*") shouldBe true
  }

  it should "match asterisk for empty string within segment" in {
    // * matches zero or more non-slash chars, so /docs/ matches /docs/*
    GlobPatternMatcher.matches("http://example.com/docs/", "http://example.com/docs/*") shouldBe true
  }

  // =========================================================================
  // Double asterisk (**) - matches any path
  // =========================================================================

  it should "match double asterisk across path separators" in {
    GlobPatternMatcher.matches(
      "http://example.com/a/b/c/d.html",
      "http://example.com/**"
    ) shouldBe true
  }

  it should "match double asterisk in middle of pattern" in {
    GlobPatternMatcher.matches(
      "http://example.com/docs/v2/api/ref.html",
      "http://example.com/docs/**/ref.html"
    ) shouldBe true
  }

  // =========================================================================
  // Question mark (?) - matches single character
  // =========================================================================

  it should "match question mark for single character" in {
    GlobPatternMatcher.matches("http://example.com/page1", "http://example.com/page?") shouldBe true
    GlobPatternMatcher.matches("http://example.com/pageA", "http://example.com/page?") shouldBe true
  }

  it should "not match question mark for slash" in {
    GlobPatternMatcher.matches("http://example.com/page/", "http://example.com/page?") shouldBe false
  }

  it should "not match question mark for empty string" in {
    GlobPatternMatcher.matches("http://example.com/page", "http://example.com/page?x") shouldBe false
  }

  // =========================================================================
  // Special character escaping
  // =========================================================================

  it should "escape dots in patterns" in {
    GlobPatternMatcher.matches("http://example.com/page.html", "http://example.com/page.html") shouldBe true
    GlobPatternMatcher.matches("http://exampleXcom/pageXhtml", "http://example.com/page.html") shouldBe false
  }

  it should "escape parentheses" in {
    GlobPatternMatcher.matches("http://example.com/page(1)", "http://example.com/page(1)") shouldBe true
  }

  it should "escape brackets" in {
    GlobPatternMatcher.matches("http://example.com/page[1]", "http://example.com/page[1]") shouldBe true
  }

  // =========================================================================
  // Case insensitivity
  // =========================================================================

  it should "match case-insensitively" in {
    GlobPatternMatcher.matches("HTTP://EXAMPLE.COM/Page", "http://example.com/page") shouldBe true
  }

  // =========================================================================
  // matchesAny
  // =========================================================================

  "GlobPatternMatcher.matchesAny" should "return true if any pattern matches" in {
    GlobPatternMatcher.matchesAny(
      "http://example.com/docs/page",
      Seq("http://other.com/*", "http://example.com/docs/*")
    ) shouldBe true
  }

  it should "return false if no patterns match" in {
    GlobPatternMatcher.matchesAny(
      "http://example.com/page",
      Seq("http://other.com/*", "http://another.com/*")
    ) shouldBe false
  }

  it should "return false for empty patterns" in {
    GlobPatternMatcher.matchesAny("http://example.com/page", Seq.empty) shouldBe false
  }

  // =========================================================================
  // filter
  // =========================================================================

  "GlobPatternMatcher.filter" should "include all when include patterns empty" in {
    val urls = Seq("http://a.com/1", "http://b.com/2")
    GlobPatternMatcher.filter(urls, Seq.empty, Seq.empty) shouldBe urls
  }

  it should "filter by include patterns" in {
    val urls   = Seq("http://a.com/docs/1", "http://a.com/api/2", "http://a.com/docs/3")
    val result = GlobPatternMatcher.filter(urls, Seq("http://a.com/docs/*"), Seq.empty)
    result should have size 2
    result.foreach(u => u should include("docs"))
  }

  it should "exclude by exclude patterns" in {
    val urls   = Seq("http://a.com/page", "http://a.com/admin", "http://a.com/settings")
    val result = GlobPatternMatcher.filter(urls, Seq.empty, Seq("http://a.com/admin"))
    result should have size 2
    result should not contain "http://a.com/admin"
  }

  it should "apply both include and exclude" in {
    val urls = Seq("http://a.com/docs/public", "http://a.com/docs/private", "http://a.com/api/v1")
    val result = GlobPatternMatcher.filter(
      urls,
      Seq("http://a.com/docs/*"),
      Seq("http://a.com/docs/private")
    )
    result shouldBe Seq("http://a.com/docs/public")
  }

  // =========================================================================
  // Cache
  // =========================================================================

  "GlobPatternMatcher.clearCache" should "not cause errors when called" in {
    GlobPatternMatcher.matches("http://example.com/test", "http://example.com/*")
    GlobPatternMatcher.clearCache()
    // Should still work after cache clear
    GlobPatternMatcher.matches("http://example.com/test", "http://example.com/*") shouldBe true
  }
}
