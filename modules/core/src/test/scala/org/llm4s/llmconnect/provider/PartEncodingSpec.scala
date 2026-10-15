package org.llm4s.llmconnect.provider

import org.llm4s.error.UnsupportedContentPartError
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class PartEncodingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val image = ContentPart.image(MediaSource.Inline(IArray[Byte](1, 2, 3)), MediaType.Png).value

  "check" should "ask only whether a part is covered, never encode it" in {
    val encoded = new AtomicInteger()
    val counting: PartialFunction[ContentPart, String] = {
      case p if p.isInstanceOf[ContentPart.Image] => encoded.incrementAndGet(); "x"
      case ContentPart.Text(t)                    => t
    }
    PartEncoding.check("p", Seq(UserMessage(Vector(ContentPart.Text("a"), image))), counting, counting) shouldBe Right(
      ()
    )
    encoded.get shouldBe 0
  }

  it should "name the first part a role does not cover" in {
    val assistant = AssistantMessage(None, Seq(ToolCall("c", "t", ujson.Obj())))
    PartEncoding
      .check(
        "p",
        Seq(UserMessage("go"), assistant, ToolMessage(Vector(image), "c")),
        { case _ => 1 },
        PartEncoding.textOnly
      )
      .left
      .value shouldBe UnsupportedContentPartError("p", image.kind, "tool")
  }

  "checkInlineLimits" should "apply a provider's own per-part limits" in {
    val messages = Seq(UserMessage(Vector(ContentPart.Text("x"), image, image)))
    PartEncoding.checkInlineLimits("p", messages, _ => Some(3L)) shouldBe Right(())
    PartEncoding.checkInlineLimits("p", messages, _ => Some(2L)).left.value.message should include("at most 2 bytes")
    PartEncoding.checkInlineLimits("p", messages, _ => None) shouldBe Right(())
    PartEncoding.base64Length(3) shouldBe 4
    PartEncoding.base64Length(4) shouldBe 8
  }

  "checkImagesPerRequest" should "count the images of every message" in {
    val messages = Seq(UserMessage(Vector(image)), AssistantMessage("ok"), UserMessage(Vector(image)))
    PartEncoding.checkImagesPerRequest("p", messages, 2) shouldBe Right(())
    PartEncoding.checkImagesPerRequest("p", messages, 1).left.value.message should
      include("at most 1 images in a request; this one has 2")
  }

  "checkInlineBytesTotal" should "count the decoded bytes of the inline parts it selects" in {
    val messages = Seq(UserMessage(Vector(ContentPart.Text("x" * 100), image, image)))
    PartEncoding.checkInlineBytesTotal("p", messages, 6L, "images")(_ => true) shouldBe Right(())
    PartEncoding.checkInlineBytesTotal("p", messages, 0L, "files")(_.isInstanceOf[ContentPart.Document]) shouldBe Right(
      ()
    )
    PartEncoding.checkInlineBytesTotal("p", messages, 5L, "images")(_ => true).left.value.message should include(
      "this one has 6"
    )
  }

  "withoutBlankText" should "drop blank text parts and keep everything else in order" in {
    PartEncoding.withoutBlankText(
      Vector(ContentPart.Text(""), image, ContentPart.Text(" "), ContentPart.Text("a"))
    ) shouldBe
      Vector(image, ContentPart.Text("a"))
  }

  "check and encodeAll" should "refuse a null part as invalid, not throw" in {
    PartEncoding
      .check("p", Seq(UserMessage(Vector(null, image))), { case _ => 1 }, PartEncoding.textOnly)
      .left
      .value
      .message should
      include("null part")
    PartEncoding
      .encodeAll("p", MessageRole.User, Vector(ContentPart.Text("a"), null)) { case _ => 1 }
      .isLeft shouldBe true
  }

  "isWebUrl" should "accept http and https only" in {
    PartEncoding.isWebUrl(URI.create("https://e.com/a")) shouldBe true
    PartEncoding.isWebUrl(URI.create("HTTP://e.com/a")) shouldBe true
    Seq("http:cat.png", "https:/cat.png", "https:///cat.png").foreach(u =>
      PartEncoding.isWebUrl(URI.create(u)) shouldBe false
    )
    PartEncoding.hasScheme(URI.create("s3://bucket/key"), "s3") shouldBe true
    PartEncoding.hasScheme(URI.create("s3:/key"), "s3") shouldBe false
    Seq("gs://b/a", "s3://b/a", "ftp://e.com/a", "relative").foreach(u =>
      PartEncoding.isWebUrl(URI.create(u)) shouldBe false
    )
  }
}
