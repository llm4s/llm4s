package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.loader.internal.UrlNormalizer

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
}
