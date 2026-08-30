package org.llm4s.media

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MediaCategorySpec extends AnyFlatSpec with Matchers {

  "MediaCategory.fromMimeType" should "resolve every category from its own top-level type" in {
    MediaCategory.all.foreach(c => MediaCategory.fromMimeType(s"${c.name}/anything") shouldBe Some(c))
  }

  it should "be case insensitive and tolerate surrounding whitespace" in {
    MediaCategory.fromMimeType("Image/PNG") shouldBe Some(MediaCategory.Image)
    MediaCategory.fromMimeType("  audio/wav ") shouldBe Some(MediaCategory.Audio)
  }

  it should "return None for an unmodelled or malformed top-level type" in {
    MediaCategory.fromMimeType("font/woff2") shouldBe None
    MediaCategory.fromMimeType("image") shouldBe None
    MediaCategory.fromMimeType("") shouldBe None
  }

  it should "not match a subtype that happens to name a category" in {
    MediaCategory.fromMimeType("application/image") shouldBe Some(MediaCategory.Application)
  }

  "MediaCategory.all" should "have distinct names" in {
    MediaCategory.all.map(_.name).distinct.size shouldBe MediaCategory.all.size
  }
}
