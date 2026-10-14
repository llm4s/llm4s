package org.llm4s.llmconnect.model

import org.llm4s.error.ValidationError
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import java.net.URI

class ContentPartSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val png    = MediaSource.Inline(IArray[Byte](-119, 80, 78, 71))
  private val remote = MediaSource.Url(URI.create("https://example.com/cat.png"))

  "ContentPart.image" should "build an image from any source within the bound" in {
    ContentPart.image(png, MediaType.Png).value.source shouldBe png
    ContentPart.image(remote, MediaType.Jpeg).value.mediaType shouldBe MediaType.Jpeg
    ContentPart.image(MediaSource.ProviderFile("openai", "file-1"), MediaType.Png).isRight shouldBe true
  }

  it should "refuse inline bytes over MaxInlineBytes, so a checkpoint never copies more" in {
    ContentPart.MaxInlineBytes shouldBe 5 * 1024 * 1024
    val atLimit = MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes)(0))
    val over    = MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes + 1)(0))
    ContentPart.image(atLimit, MediaType.Png).isRight shouldBe true
    val error = ContentPart.image(over, MediaType.Png).left.value
    error shouldBe a[ValidationError]
    error.message should include("5242881 bytes")
    error.message should include("Url or a ProviderFile")
  }

  it should "refuse inline media of no bytes" in {
    ContentPart.image(MediaSource.Inline(IArray.empty[Byte]), MediaType.Png).left.value.message should include(
      "no bytes"
    )
    ContentPart.document(MediaSource.Inline(IArray.empty[Byte]), MediaType.Pdf).isLeft shouldBe true
  }

  it should "refuse a relative URL and a data: URL, which is inline bytes by another name" in {
    ContentPart.image(MediaSource.Url(URI.create("cat.png")), MediaType.Png).left.value.message should
      include("absolute")
    ContentPart
      .image(MediaSource.Url(URI.create("DATA:image/png;base64,iVBO")), MediaType.Png)
      .left
      .value
      .message should include("MediaSource.Inline")
  }

  it should "refuse a provider file with a blank provider or id" in {
    ContentPart.image(MediaSource.ProviderFile(" ", "file-1"), MediaType.Png).isLeft shouldBe true
    ContentPart.document(MediaSource.ProviderFile("openai", ""), MediaType.Pdf).isLeft shouldBe true
  }

  "ContentPart.document" should "apply the same checks" in {
    ContentPart.document(remote, MediaType.Pdf).value.mediaType shouldBe MediaType.Pdf
    ContentPart
      .document(MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes + 1)(0)), MediaType.Csv)
      .isLeft shouldBe
      true
  }

  "The media constructors" should "refuse null arguments with a ValidationError, never build a part holding one" in {
    ContentPart.image(null, MediaType.Png).left.value.message should include("needs a source")
    ContentPart.image(png, null).left.value.message should include("needs a media type")
    ContentPart.document(MediaSource.Url(null), MediaType.Pdf).left.value.message should include("needs a URI")
    ContentPart
      .image(MediaSource.Inline(null.asInstanceOf[IArray[Byte]]), MediaType.Png)
      .left
      .value
      .message should include("needs its bytes")
    ContentPart.document(MediaSource.ProviderFile(null, "f"), MediaType.Pdf).isLeft shouldBe true
    ContentPart.document(MediaSource.ProviderFile("openai", null), MediaType.Pdf).isLeft shouldBe true
  }

  "kind" should "name the part, its source and its media type" in {
    ContentPart.Text("hi").kind shouldBe "text"
    ContentPart.image(remote, MediaType.Png).value.kind shouldBe "image (url, image/png)"
    ContentPart.document(png, MediaType.Pdf).value.kind shouldBe "document (inline, application/pdf)"
    ContentPart.image(MediaSource.ProviderFile("gemini", "files/1"), MediaType.WebP).value.kind shouldBe
      "image (provider file gemini, image/webp)"
  }

  "text" should "join the text parts and skip the others" in {
    val image = ContentPart.image(png, MediaType.Png).value
    ContentPart.text(Vector(ContentPart.Text("a"), image, ContentPart.Text("b"))) shouldBe "a\nb"
    ContentPart.text(Vector(image)) shouldBe ""
  }

  "mapText" should "rewrite each text part in place and keep the media beside it" in {
    val image = ContentPart.image(png, MediaType.Png).value
    ContentPart.mapText(Vector(ContentPart.Text("a"), image, ContentPart.Text("b"), image))(_.toUpperCase) shouldBe
      Vector(ContentPart.Text("A"), image, ContentPart.Text("B"), image)
  }

  "estimatedTokens" should "give media a cost, erring high, and text none" in {
    ContentPart.estimatedTokens(ContentPart.Text("hello")) shouldBe 0
    ContentPart.estimatedTokens(
      ContentPart.image(remote, MediaType.Png).value
    ) shouldBe ContentPart.EstimatedImageTokens
    val csv = MediaSource.Inline(IArray.fill[Byte](4000)(65))
    ContentPart.estimatedTokens(ContentPart.document(csv, MediaType.Csv).value) shouldBe 1000
    val pdf = MediaSource.Inline(IArray.fill[Byte](320000)(1))
    ContentPart.estimatedTokens(ContentPart.document(pdf, MediaType.Pdf).value) shouldBe 10000
    ContentPart.estimatedTokens(
      ContentPart.document(png, MediaType.Pdf).value
    ) shouldBe ContentPart.EstimatedDocumentTokens
    ContentPart.estimatedTokens(ContentPart.document(remote, MediaType.Markdown).value) shouldBe
      ContentPart.EstimatedReferencedDocumentTokens
    ContentPart.estimatedTokens(
      ContentPart.document(MediaSource.ProviderFile("openai", "f"), MediaType.Pdf).value
    ) shouldBe
      ContentPart.EstimatedReferencedDocumentTokens
  }

  "inlineBytes" should "total the inline parts" in {
    val image = ContentPart.image(png, MediaType.Png).value
    val doc   = ContentPart.document(remote, MediaType.Pdf).value
    ContentPart.inlineBytes(Vector(image, ContentPart.Text("x"), doc, image)) shouldBe 8L
  }

  "The codec" should "round-trip every part and source, inline bytes as base64" in {
    val parts: Vector[ContentPart] = Vector(
      ContentPart.Text("look"),
      ContentPart.image(png, MediaType.Png).value,
      ContentPart.image(remote, MediaType.Gif).value,
      ContentPart.document(MediaSource.ProviderFile("openai", "file-9"), MediaType.Pdf).value,
      ContentPart.document(MediaSource.Inline(IArray.from("a,b".getBytes("UTF-8"))), MediaType.Csv).value
    )
    read[Vector[ContentPart]](write(parts)) shouldBe parts
    val json = ujson.read(write[ContentPart](parts(1)))
    json shouldBe ujson.Obj(
      "type"      -> "image",
      "mediaType" -> "image/png",
      "source"    -> ujson.Obj("type" -> "inline", "data" -> "iVBORw==")
    )
    ujson.read(write[ContentPart](parts(3)))("source") shouldBe
      ujson.Obj("type" -> "providerFile", "provider" -> "openai", "id" -> "file-9")
  }

  it should "read stored or imported JSON through the same checks a built part passes" in {
    def imageJson(source: ujson.Value) = ujson.Obj("type" -> "image", "mediaType" -> "image/png", "source" -> source)
    val oversized = java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](ContentPart.MaxInlineBytes + 1)(0))
    Seq(
      imageJson(ujson.Obj("type" -> "inline", "data" -> oversized)),
      imageJson(ujson.Obj("type" -> "url", "uri" -> "relative.png")),
      imageJson(ujson.Obj("type" -> "url", "uri" -> "data:image/png;base64,iVBO")),
      imageJson(ujson.Obj("type" -> "providerFile", "provider" -> " ", "id" -> "f"))
    ).foreach(json => an[Exception] should be thrownBy read[ContentPart](json))
  }

  it should "refuse a media type of the wrong family, and an unknown part" in {
    val wrongFamily = ujson.Obj(
      "type"      -> "image",
      "mediaType" -> "application/pdf",
      "source"    -> ujson.Obj("type" -> "url", "uri" -> "https://example.com/a.pdf")
    )
    an[Exception] should be thrownBy read[ContentPart](wrongFamily)
    an[Exception] should be thrownBy read[ContentPart](ujson.Obj("type" -> "audio"))
    an[Exception] should be thrownBy
      read[ContentPart](
        ujson.Obj("type" -> "document", "mediaType" -> "text/csv", "source" -> ujson.Obj("type" -> "x"))
      )
  }
}
