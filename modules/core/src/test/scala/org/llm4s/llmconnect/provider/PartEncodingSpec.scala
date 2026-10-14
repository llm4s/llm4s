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

  "isWebUrl" should "accept http and https only" in {
    PartEncoding.isWebUrl(URI.create("https://e.com/a")) shouldBe true
    PartEncoding.isWebUrl(URI.create("HTTP://e.com/a")) shouldBe true
    Seq("gs://b/a", "s3://b/a", "ftp://e.com/a", "relative").foreach(u =>
      PartEncoding.isWebUrl(URI.create(u)) shouldBe false
    )
  }
}
