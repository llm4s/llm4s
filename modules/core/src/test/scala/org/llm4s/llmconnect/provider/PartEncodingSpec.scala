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

  "checkPartCounts" should "refuse a message with more parts of a kind than the provider takes" in {
    val key: ContentPart => Option[String] = p => Option.when(p.isInstanceOf[ContentPart.Image])("image")
    val two                                = Seq(UserMessage(Vector(image, image)))
    PartEncoding.checkPartCounts("p", two, key, Map("image" -> 2)) shouldBe Right(())
    PartEncoding.checkPartCounts("p", two, key, Map("image" -> 1)).left.value.message should
      include("at most 1 images in a message; message 0 has 2")
  }

  "checkInlineMediaTotal" should "count the decoded bytes of every inline part" in {
    val messages = Seq(UserMessage(Vector(ContentPart.Text("x" * 100), image, image)))
    PartEncoding.checkInlineMediaTotal("p", messages, 6L) shouldBe Right(())
    PartEncoding.checkInlineMediaTotal("p", messages, 5L).left.value.message should include("this one has 6")
  }

  "withoutBlankText" should "drop blank text parts and keep everything else in order" in {
    PartEncoding.withoutBlankText(
      Vector(ContentPart.Text(""), image, ContentPart.Text(" "), ContentPart.Text("a"))
    ) shouldBe
      Vector(image, ContentPart.Text("a"))
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
