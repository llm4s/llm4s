package org.llm4s.media

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MediaTypeSpec extends AnyFlatSpec with Matchers {

  "MediaType" should "give every type a distinct MIME string and a category matching it" in {
    MediaType.all.map(_.mimeType).distinct.size shouldBe MediaType.all.size
    MediaType.all.foreach(t => MediaCategory.fromMimeType(t.mimeType) shouldBe Some(t.category))
  }

  it should "give every type a distinct canonical extension that round-trips" in {
    MediaType.all.map(_.extension).distinct.size shouldBe MediaType.all.size
    MediaType.all.foreach(t => MediaType.fromExtension(t.extension) shouldBe Some(t))
  }

  it should "round-trip every type through its MIME string" in {
    MediaType.all.foreach(t => MediaType.fromMimeType(t.mimeType) shouldBe Some(t))
  }

  it should "partition all into images, audio and documents" in {
    MediaType.images.foreach(_.category shouldBe MediaCategory.Image)
    MediaType.audio.foreach(_.category shouldBe MediaCategory.Audio)
    MediaType.all should contain theSameElementsAs (MediaType.images ++ MediaType.audio ++ MediaType.documents)
  }

  "the document types" should "be PDF, an application type, and four text types" in {
    MediaType.documents shouldBe Seq(
      MediaType.Pdf,
      MediaType.PlainText,
      MediaType.Markdown,
      MediaType.Html,
      MediaType.Csv
    )
    MediaType.Pdf.category shouldBe MediaCategory.Application
    MediaType.documents.tail.foreach(_.category shouldBe MediaCategory.Text)
  }

  they should "resolve from their extensions, aliases and MIME strings" in {
    MediaType.fromExtension("pdf") shouldBe Some(MediaType.Pdf)
    MediaType.fromExtension("txt") shouldBe Some(MediaType.PlainText)
    MediaType.fromExtension("md") shouldBe Some(MediaType.Markdown)
    MediaType.fromExtension("markdown") shouldBe Some(MediaType.Markdown)
    MediaType.fromExtension("htm") shouldBe Some(MediaType.Html)
    MediaType.fromPath("data/report.csv") shouldBe Some(MediaType.Csv)
    MediaType.fromMimeType("application/pdf") shouldBe Some(MediaType.Pdf)
    MediaType.fromMimeType("text/x-markdown") shouldBe Some(MediaType.Markdown)
    MediaType.fromMimeType("text/html; charset=utf-8") shouldBe Some(MediaType.Html)
  }

  "fromExtension" should "be case insensitive and tolerate a leading dot" in {
    MediaType.fromExtension("PNG") shouldBe Some(MediaType.Png)
    MediaType.fromExtension(".Png") shouldBe Some(MediaType.Png)
    MediaType.fromExtension("  png  ") shouldBe Some(MediaType.Png)
  }

  it should "resolve non-canonical spellings" in {
    MediaType.fromExtension("jpeg") shouldBe Some(MediaType.Jpeg)
    MediaType.fromExtension("jpg") shouldBe Some(MediaType.Jpeg)
    MediaType.fromExtension("tif") shouldBe Some(MediaType.Tiff)
    MediaType.fromExtension("wave") shouldBe Some(MediaType.Wav)
  }

  it should "return None for an unknown extension rather than guessing" in {
    MediaType.fromExtension("xyz") shouldBe None
    MediaType.fromExtension("") shouldBe None
  }

  "fromPath" should "read the extension off a path or URL" in {
    MediaType.fromPath("photo.png") shouldBe Some(MediaType.Png)
    MediaType.fromPath("/var/tmp/photo.JPEG") shouldBe Some(MediaType.Jpeg)
    MediaType.fromPath("https://example.com/a/b/clip.mp3") shouldBe Some(MediaType.Mp3)
  }

  it should "use the last dot, not the first" in {
    MediaType.fromPath("my.photo.backup.png") shouldBe Some(MediaType.Png)
  }

  it should "not read a dot from a directory name as the file's extension" in {
    MediaType.fromPath("/home/user.png/README") shouldBe None
    MediaType.fromPath("/home/v1.2/photo.gif") shouldBe Some(MediaType.Gif)
  }

  it should "return None for a path with no extension" in {
    MediaType.fromPath("README") shouldBe None
    MediaType.fromPath("") shouldBe None
  }

  "fromMimeType" should "ignore parameters and case" in {
    MediaType.fromMimeType("IMAGE/PNG") shouldBe Some(MediaType.Png)
    MediaType.fromMimeType("TEXT/PLAIN; charset=utf-8") shouldBe Some(MediaType.PlainText)
    MediaType.fromMimeType("image/png; charset=binary") shouldBe Some(MediaType.Png)
  }

  it should "resolve alternates seen in the wild" in {
    MediaType.fromMimeType("image/jpg") shouldBe Some(MediaType.Jpeg)
    MediaType.fromMimeType("audio/x-wav") shouldBe Some(MediaType.Wav)
  }

  it should "return None for a MIME type it does not model" in {
    MediaType.fromMimeType("application/json") shouldBe None
    MediaType.fromMimeType("video/mp4") shouldBe None
  }

  "the image-only lookups" should "resolve images" in {
    MediaType.imageFromExtension("bmp") shouldBe Some(MediaType.Bmp)
    MediaType.imageFromPath("a/b/c.tiff") shouldBe Some(MediaType.Tiff)
    MediaType.imageFromMimeType("image/webp") shouldBe Some(MediaType.WebP)
  }

  it should "reject a type that resolves but is not an image" in {
    MediaType.fromExtension("wav") shouldBe Some(MediaType.Wav)
    MediaType.imageFromExtension("wav") shouldBe None
    MediaType.imageFromPath("song.mp3") shouldBe None
    MediaType.imageFromMimeType("audio/ogg") shouldBe None
  }
}
