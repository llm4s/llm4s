package org.llm4s.media

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class MediaSourceSpec extends AnyFlatSpec with Matchers {

  "An inline source" should "be equal to another with the same bytes, and hash alike" in {
    val a = MediaSource.Inline(IArray[Byte](1, 2, 3))
    val b = MediaSource.Inline(IArray[Byte](1, 2, 3))
    a shouldBe b
    a.hashCode shouldBe b.hashCode
    a should not be MediaSource.Inline(IArray[Byte](1, 2, 4))
    a should not be MediaSource.Url(URI.create("https://example.com/a.png"))
  }

  it should "print its length, never its bytes" in {
    MediaSource.Inline(IArray[Byte](1, 2, 3)).toString shouldBe "Inline(3 bytes)"
  }

  it should "report its length" in {
    MediaSource.Inline(IArray.fill[Byte](10)(0)).length shouldBe 10
  }

  "The other sources" should "be plain data" in {
    MediaSource.Url(URI.create("https://example.com/a.png")).uri.getHost shouldBe "example.com"
    MediaSource.ProviderFile("openai", "file-1") shouldBe MediaSource.ProviderFile("openai", "file-1")
  }

  "kind" should "name each source" in {
    MediaSource.Url(URI.create("https://example.com/a.png")).kind shouldBe "url"
    MediaSource.Inline(IArray.empty[Byte]).kind shouldBe "inline"
    MediaSource.ProviderFile("gemini", "files/x").kind shouldBe "provider file gemini"
  }
}
