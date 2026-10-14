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

  "checkInlineLimits and checkRequestSize" should "apply a provider's own per-part and per-request limits" in {
    val messages = Seq(UserMessage(Vector(ContentPart.Text("x"), image, image)))
    PartEncoding.checkInlineLimits("p", messages, _ => Some(3L)) shouldBe Right(())
    PartEncoding.checkInlineLimits("p", messages, _ => Some(2L)).left.value.message should include("at most 2 bytes")
    PartEncoding.checkInlineLimits("p", messages, _ => None) shouldBe Right(())
    // two 3-byte images are 8 bytes base64-encoded, and the text "x" one more
    PartEncoding.checkRequestSize("p", messages, 9L) shouldBe Right(())
    PartEncoding.checkRequestSize("p", messages, 8L).left.value.message should include("this one has 9")
    val withSystem = SystemMessage("abcd") +: messages
    PartEncoding.checkRequestSize("p", withSystem, 9L).left.value.message should include("this one has 13")
    val call = AssistantMessage(None, Seq(ToolCall("c", "t", ujson.Obj("a" -> 1))))
    PartEncoding.checkRequestSize("p", Seq(call), 1L).left.value.message should include("this one has 8")
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

  "isWebUrl" should "accept http and https only" in {
    PartEncoding.isWebUrl(URI.create("https://e.com/a")) shouldBe true
    PartEncoding.isWebUrl(URI.create("HTTP://e.com/a")) shouldBe true
    Seq("gs://b/a", "s3://b/a", "ftp://e.com/a", "relative").foreach(u =>
      PartEncoding.isWebUrl(URI.create(u)) shouldBe false
    )
  }
}
