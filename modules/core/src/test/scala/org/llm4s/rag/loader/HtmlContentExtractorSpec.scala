package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.rag.loader.internal.HtmlContentExtractor

class HtmlContentExtractorSpec extends AnyFlatSpec with Matchers {

  // =========================================================================
  // Title extraction
  // =========================================================================

  "HtmlContentExtractor" should "extract title from <title> tag" in {
    val html   = "<html><head><title>My Page</title></head><body>Content</body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.title shouldBe "My Page"
  }

  it should "fall back to <h1> when no <title> tag" in {
    val html   = "<html><body><h1>Heading Title</h1><p>Content</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.title shouldBe "Heading Title"
  }

  it should "fall back to og:title when no <title> or <h1>" in {
    val html = """<html><head><meta property="og:title" content="OG Title"></head><body><p>Content</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.title shouldBe "OG Title"
  }

  it should "return empty string when no title source available" in {
    val html   = "<html><body><p>Content only</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.title shouldBe ""
  }

  // =========================================================================
  // Description extraction
  // =========================================================================

  it should "extract meta description" in {
    val html = """<html><head><meta name="description" content="Page description"></head><body>Content</body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.description shouldBe Some("Page description")
  }

  it should "fall back to og:description" in {
    val html =
      """<html><head><meta property="og:description" content="OG desc"></head><body>Content</body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.description shouldBe Some("OG desc")
  }

  it should "return None when no description" in {
    val html   = "<html><body>Content</body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.description shouldBe None
  }

  // =========================================================================
  // Content extraction - element removal
  // =========================================================================

  it should "remove script tags from content" in {
    val html   = "<html><body><p>Visible</p><script>alert('hidden')</script></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("alert")
    result.content should include("Visible")
  }

  it should "remove style tags from content" in {
    val html   = "<html><body><style>.hidden{display:none}</style><p>Visible</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("display:none")
  }

  it should "remove nav elements" in {
    val html   = "<html><body><nav>Menu items</nav><p>Main content</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("Menu items")
    result.content should include("Main content")
  }

  it should "remove footer elements" in {
    val html   = "<html><body><p>Content</p><footer>Footer text</footer></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("Footer text")
  }

  it should "remove elements with role=navigation" in {
    val html   = """<html><body><div role="navigation">Nav</div><p>Content</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("Nav")
  }

  it should "remove elements with aria-hidden=true" in {
    val html   = """<html><body><div aria-hidden="true">Hidden</div><p>Visible</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("Hidden")
  }

  it should "remove cookie banner elements" in {
    val html   = """<html><body><div class="cookie-banner">Accept cookies</div><p>Content</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("Accept cookies")
  }

  it should "remove iframe elements" in {
    val html   = """<html><body><iframe src="http://example.com/ad"></iframe><p>Content</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("iframe")
  }

  // =========================================================================
  // Content extraction - structure preservation
  // =========================================================================

  it should "preserve heading structure" in {
    val html = "<html><body><main><h2>Section 1</h2><p>Text 1</p><h3>Subsection</h3><p>Text 2</p></main></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Section 1")
    result.content should include("Text 1")
    result.content should include("Subsection")
  }

  it should "format list items with bullet points" in {
    val html   = "<html><body><main><ul><li>Item 1</li><li>Item 2</li></ul></main></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Item 1")
    result.content should include("Item 2")
  }

  it should "format code blocks" in {
    val html   = "<html><body><main><pre>code here</pre></main></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("code here")
  }

  it should "format blockquotes" in {
    val html   = "<html><body><main><blockquote>A wise quote</blockquote></main></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("A wise quote")
  }

  it should "format table content" in {
    val html =
      """<html><body><main><table>
        |<tr><th>Name</th><th>Value</th></tr>
        |<tr><td>Foo</td><td>Bar</td></tr>
        |</table></main></body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Name")
    result.content should include("Foo")
    result.content should include("Bar")
  }

  // =========================================================================
  // Main content detection
  // =========================================================================

  it should "prefer <main> element for content" in {
    val html =
      """<html><body>
        |<div>Sidebar content</div>
        |<main><p>Main content here</p></main>
        |</body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Main content here")
  }

  it should "prefer <article> element when no <main>" in {
    val html =
      """<html><body>
        |<div>Sidebar</div>
        |<article><p>Article content</p></article>
        |</body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Article content")
  }

  it should "fall back to body when no main content container found" in {
    val html   = "<html><body><p>Body content</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Body content")
  }

  // =========================================================================
  // Link extraction
  // =========================================================================

  it should "extract absolute HTTP links" in {
    val html =
      """<html><body>
        |<a href="http://example.com/page1">Link 1</a>
        |<a href="https://example.com/page2">Link 2</a>
        |</body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.links should contain("http://example.com/page1")
    result.links should contain("https://example.com/page2")
  }

  it should "resolve relative links against base URL" in {
    val html   = """<html><body><a href="/about">About</a></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.links should contain("http://example.com/about")
  }

  it should "exclude non-HTTP links" in {
    val html =
      """<html><body>
        |<a href="mailto:test@example.com">Email</a>
        |<a href="javascript:void(0)">JS</a>
        |<a href="ftp://files.example.com">FTP</a>
        |<a href="http://example.com/valid">Valid</a>
        |</body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.links should contain only "http://example.com/valid"
  }

  it should "deduplicate links" in {
    val html =
      """<html><body>
        |<a href="http://example.com/page">Link 1</a>
        |<a href="http://example.com/page">Link 2</a>
        |</body></html>""".stripMargin
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.links should have size 1
  }

  it should "exclude links without href attribute" in {
    val html   = """<html><body><a>No href at all</a><p>Content</p></body></html>"""
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.links shouldBe empty
  }

  // =========================================================================
  // extractLinksOnly
  // =========================================================================

  "HtmlContentExtractor.extractLinksOnly" should "return just the links" in {
    val html  = """<html><body><a href="http://example.com/page">Link</a></body></html>"""
    val links = HtmlContentExtractor.extractLinksOnly(html, "http://example.com")
    links should contain("http://example.com/page")
  }

  // =========================================================================
  // Edge cases
  // =========================================================================

  it should "handle empty HTML gracefully" in {
    val result = HtmlContentExtractor.extract("", "http://example.com")
    result.title shouldBe ""
    result.links shouldBe empty
  }

  it should "handle malformed HTML" in {
    val html   = "<html><body><p>Unclosed paragraph<div>Mixed up"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    result.content should include("Unclosed paragraph")
  }

  it should "handle noscript elements" in {
    val html   = "<html><body><noscript>JS disabled</noscript><p>Content</p></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("JS disabled")
  }

  it should "collapse excessive whitespace" in {
    val html   = "<html><body><main><p>Hello     world</p></main></body></html>"
    val result = HtmlContentExtractor.extract(html, "http://example.com")
    (result.content should not).include("     ")
  }
}
