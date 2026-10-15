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
