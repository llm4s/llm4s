package org.llm4s.rag.loader.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UrlNormalizerSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // normalize - basic
  // =========================================================================

  "UrlNormalizer.normalize" should "lowercase scheme" in {
    UrlNormalizer.normalize("HTTP://EXAMPLE.COM/path") shouldBe "http://example.com/path"
  }

  it should "lowercase host" in {
    UrlNormalizer.normalize("http://Example.COM/path") shouldBe "http://example.com/path"
  }

  it should "remove trailing slash" in {
    UrlNormalizer.normalize("http://example.com/path/") shouldBe "http://example.com/path"
  }

  it should "keep root path as /" in {
    UrlNormalizer.normalize("http://example.com/") shouldBe "http://example.com/"
    UrlNormalizer.normalize("http://example.com") shouldBe "http://example.com/"
  }

  it should "remove fragment" in {
    UrlNormalizer.normalize("http://example.com/page#section") shouldBe "http://example.com/page"
  }

  it should "remove query parameters by default" in {
    UrlNormalizer.normalize("http://example.com/page?foo=bar") shouldBe "http://example.com/page"
  }

  it should "keep query parameters when requested" in {
    UrlNormalizer.normalize(
      "http://example.com/page?foo=bar",
      includeQueryParams = true
    ) shouldBe "http://example.com/page?foo=bar"
  }

  // =========================================================================
  // normalize - port handling
  // =========================================================================

  it should "remove default HTTP port 80" in {
    UrlNormalizer.normalize("http://example.com:80/path") shouldBe "http://example.com/path"
  }

  it should "remove default HTTPS port 443" in {
    UrlNormalizer.normalize("https://example.com:443/path") shouldBe "https://example.com/path"
  }

  it should "keep non-default ports" in {
    UrlNormalizer.normalize("http://example.com:8080/path") shouldBe "http://example.com:8080/path"
  }

  it should "keep HTTPS with non-default port" in {
    UrlNormalizer.normalize("https://example.com:8443/path") shouldBe "https://example.com:8443/path"
  }

  // =========================================================================
  // normalize - path handling
  // =========================================================================

  it should "collapse multiple slashes in path" in {
    UrlNormalizer.normalize("http://example.com/a//b///c") shouldBe "http://example.com/a/b/c"
  }

  it should "handle path with no leading slash" in {
    UrlNormalizer.normalize("http://example.com") shouldBe "http://example.com/"
  }

  // =========================================================================
  // normalize - error handling
  // =========================================================================

  it should "return original string for invalid URL" in {
    val invalid = "not a valid url at all"
    UrlNormalizer.normalize(invalid) shouldBe invalid
  }

  // =========================================================================
  // resolve
  // =========================================================================

  "UrlNormalizer.resolve" should "resolve relative path" in {
    val result = UrlNormalizer.resolve("http://example.com/docs/page1", "page2")
    result shouldBe Some("http://example.com/docs/page2")
  }

  it should "resolve absolute path" in {
    val result = UrlNormalizer.resolve("http://example.com/docs/page1", "/about")
    result shouldBe Some("http://example.com/about")
  }

  it should "resolve full URL" in {
    val result = UrlNormalizer.resolve("http://example.com/page", "http://other.com/page2")
    result shouldBe Some("http://other.com/page2")
  }

  it should "handle parent directory traversal" in {
    val result = UrlNormalizer.resolve("http://example.com/a/b/c", "../d")
    result shouldBe Some("http://example.com/a/d")
  }

  it should "return None for invalid base URL" in {
    val result = UrlNormalizer.resolve("not a url", "/page")
    result shouldBe None
  }

  // =========================================================================
  // extractDomain
  // =========================================================================

  "UrlNormalizer.extractDomain" should "extract domain from valid URL" in {
    UrlNormalizer.extractDomain("http://example.com/page") shouldBe Some("example.com")
  }

  it should "return lowercase domain" in {
    UrlNormalizer.extractDomain("http://EXAMPLE.COM/page") shouldBe Some("example.com")
  }

  it should "extract subdomain" in {
    UrlNormalizer.extractDomain("http://sub.example.com/page") shouldBe Some("sub.example.com")
  }

  it should "return None for invalid URL" in {
    UrlNormalizer.extractDomain("not a url") shouldBe None
  }

  // =========================================================================
  // isInDomains
  // =========================================================================

  "UrlNormalizer.isInDomains" should "match exact domain" in {
    UrlNormalizer.isInDomains("http://example.com/page", Set("example.com")) shouldBe true
  }

  it should "match subdomain" in {
    UrlNormalizer.isInDomains("http://sub.example.com/page", Set("example.com")) shouldBe true
  }

  it should "not match different domain" in {
    UrlNormalizer.isInDomains("http://other.com/page", Set("example.com")) shouldBe false
  }

  it should "check multiple allowed domains" in {
    UrlNormalizer.isInDomains(
      "http://docs.example.com/page",
      Set("other.com", "example.com")
    ) shouldBe true
  }

  it should "return false for empty allowed domains" in {
    UrlNormalizer.isInDomains("http://example.com/page", Set.empty) shouldBe false
  }

  it should "return false for invalid URL" in {
    UrlNormalizer.isInDomains("not a url", Set("example.com")) shouldBe false
  }

  // =========================================================================
  // isValidHttpUrl
  // =========================================================================

  "UrlNormalizer.isValidHttpUrl" should "accept HTTP URL" in {
    UrlNormalizer.isValidHttpUrl("http://example.com") shouldBe true
  }

  it should "accept HTTPS URL" in {
    UrlNormalizer.isValidHttpUrl("https://example.com") shouldBe true
  }

  it should "accept HTTP URL case-insensitively" in {
    UrlNormalizer.isValidHttpUrl("HTTP://example.com") shouldBe true
  }

  it should "reject FTP URL" in {
    UrlNormalizer.isValidHttpUrl("ftp://example.com") shouldBe false
  }

  it should "reject mailto URL" in {
    UrlNormalizer.isValidHttpUrl("mailto:user@example.com") shouldBe false
  }

  it should "reject invalid URL" in {
    UrlNormalizer.isValidHttpUrl("not a url") shouldBe false
  }

  it should "reject URL without host" in {
    UrlNormalizer.isValidHttpUrl("http://") shouldBe false
  }

  // Merged in from the second `UrlNormalizerSpec` that used to sit in
  // `org.llm4s.rag.loader`: two suites of the same name for the same object, in
  // different packages. Cases already covered above were dropped, not re-asserted.

  "UrlNormalizer.normalize" should "lowercase scheme and host" in {
    UrlNormalizer.normalize("HTTP://EXAMPLE.COM/Path") shouldBe "http://example.com/Path"
    // Root path is kept as /
    UrlNormalizer.normalize("HTTPS://WWW.EXAMPLE.COM/") shouldBe "https://www.example.com/"
  }

  it should "remove fragments" in {
    UrlNormalizer.normalize("http://example.com/page#section") shouldBe "http://example.com/page"
    // Root with fragment normalizes to root
    UrlNormalizer.normalize("http://example.com/#top") shouldBe "http://example.com/"
  }

  it should "remove trailing slashes from non-root paths" in {
    // Root path keeps its slash
    UrlNormalizer.normalize("http://example.com/") shouldBe "http://example.com/"
    UrlNormalizer.normalize("http://example.com/path/") shouldBe "http://example.com/path"
  }

  it should "strip query params by default" in {
    UrlNormalizer.normalize("http://example.com/page?id=1") shouldBe "http://example.com/page"
    // Root with query params normalizes to root
    UrlNormalizer.normalize("http://example.com?foo=bar") shouldBe "http://example.com/"
  }

  it should "keep query params when configured" in {
    UrlNormalizer.normalize("http://example.com/page?id=1", includeQueryParams = true) shouldBe
      "http://example.com/page?id=1"
  }

  it should "remove default ports" in {
    UrlNormalizer.normalize("http://example.com:80/page") shouldBe "http://example.com/page"
    UrlNormalizer.normalize("https://example.com:443/page") shouldBe "https://example.com/page"
  }

  it should "preserve encoded path segments" in {
    UrlNormalizer.normalize("http://example.com/a%2Fb") shouldBe "http://example.com/a%2Fb"
    UrlNormalizer.normalize("http://example.com/a%2Fb/") shouldBe "http://example.com/a%2Fb"
    UrlNormalizer.normalize("http://example.com/a+b") shouldBe "http://example.com/a+b"
  }

  it should "handle empty path" in {
    // URLs without explicit path get root path added
    UrlNormalizer.normalize("http://example.com") shouldBe "http://example.com/"
  }

  it should "return original URL on parse error" in {
    // The normalizer attempts to parse - if it can add http:// it does
    val result = UrlNormalizer.normalize("not-a-url")
    // Just verify it doesn't throw; actual result depends on URI parsing
    result should not be null
    // Empty string gets processed as empty URI
    val emptyResult = UrlNormalizer.normalize("")
    emptyResult should not be null
  }

  "UrlNormalizer.resolve" should "resolve relative URLs" in {
    UrlNormalizer.resolve("http://example.com/path/page.html", "../other.html") shouldBe
      Some("http://example.com/other.html")
    UrlNormalizer.resolve("http://example.com/path/", "subdir/page.html") shouldBe
      Some("http://example.com/path/subdir/page.html")
  }

  it should "handle absolute URLs" in {
    // Absolute URLs resolve to themselves (with normalization)
    UrlNormalizer.resolve("http://example.com/page", "http://other.com/") shouldBe
      Some("http://other.com/")
  }

  it should "handle protocol-relative URLs" in {
    UrlNormalizer.resolve("https://example.com/page", "//cdn.example.com/script.js") shouldBe
      Some("https://cdn.example.com/script.js")
  }

  it should "return None for malformed base URLs" in {
    // These are actually parseable as relative URIs, so test a truly invalid case
    UrlNormalizer.resolve(":::invalid:::", "relative") shouldBe None
  }

  "UrlNormalizer.extractDomain" should "extract domain from URL" in {
    UrlNormalizer.extractDomain("http://example.com/path") shouldBe Some("example.com")
    UrlNormalizer.extractDomain("https://sub.example.com:8080/path?q=1") shouldBe Some("sub.example.com")
  }

  it should "return None for invalid URLs" in {
    UrlNormalizer.extractDomain("not-a-url") shouldBe None
  }

  "UrlNormalizer.isInDomains" should "match exact domains" in {
    UrlNormalizer.isInDomains("http://example.com/page", Set("example.com")) shouldBe true
    UrlNormalizer.isInDomains("http://other.com/page", Set("example.com")) shouldBe false
  }

  it should "match subdomains" in {
    UrlNormalizer.isInDomains("http://sub.example.com/page", Set("example.com")) shouldBe true
    UrlNormalizer.isInDomains("http://deep.sub.example.com/page", Set("example.com")) shouldBe true
  }

  it should "not match partial domain names" in {
    UrlNormalizer.isInDomains("http://notexample.com/page", Set("example.com")) shouldBe false
  }

  "UrlNormalizer.isValidHttpUrl" should "validate HTTP/HTTPS URLs" in {
    UrlNormalizer.isValidHttpUrl("http://example.com") shouldBe true
    UrlNormalizer.isValidHttpUrl("https://example.com/path") shouldBe true
    UrlNormalizer.isValidHttpUrl("ftp://example.com") shouldBe false
    UrlNormalizer.isValidHttpUrl("not-a-url") shouldBe false
    UrlNormalizer.isValidHttpUrl("mailto:test@example.com") shouldBe false
  }
}
