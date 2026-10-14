package org.llm4s.llmconnect.model

import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import java.net.URI

class MessagePartsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val image =
    ContentPart.image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png).value
  private val pdf = ContentPart.document(MediaSource.Inline(IArray[Byte](37, 80, 68, 70)), MediaType.Pdf).value

  "UserMessage(text)" should "be the one-part message" in {
    UserMessage("hello").parts shouldBe Vector(ContentPart.Text("hello"))
    UserMessage("hello").content shouldBe "hello"
    ToolMessage("result", "call-1").parts shouldBe Vector(ContentPart.Text("result"))
  }

  "content" should "be the text view: the text parts, joined" in {
    UserMessage(Vector(ContentPart.Text("what is"), image, ContentPart.Text("this?"))).content shouldBe "what is\nthis?"
    ToolMessage(Vector(image), "call-1").content shouldBe ""
  }

  "toString" should "name the media parts, never print their bytes" in {
    UserMessage(Vector(ContentPart.Text("see"), pdf)).toString shouldBe
      "user: see [document (inline, application/pdf)]"
  }

  "validate" should "accept a message with any content, and refuse one with none" in {
    UserMessage(Vector(image)).validate.isRight shouldBe true
    ToolMessage(Vector(pdf), "call-1").validate.isRight shouldBe true
    UserMessage(Vector.empty).validate.isLeft shouldBe true
    UserMessage(Vector(ContentPart.Text(" "), ContentPart.Text(""))).validate.isLeft shouldBe true
    ToolMessage(Vector.empty, "call-1").validate.isLeft shouldBe true
    ToolMessage(Vector(image), " ").validate.isLeft shouldBe true
  }

  "Message.hasMedia" should "say whether any message carries an image or a document" in {
    Message.hasMedia(Seq(SystemMessage("s"), UserMessage("text"), ToolMessage("r", "c"))) shouldBe false
    Message.hasMedia(Seq(UserMessage("text"), ToolMessage(Vector(image), "c"))) shouldBe true
  }

  "The Message codec" should "write a one-text message in the shape it had before parts" in {
    ujson.read(write[Message](UserMessage("hi"))) shouldBe ujson.Obj("type" -> "user", "content" -> "hi")
    ujson.read(write[Message](ToolMessage("r", "c1"))) shouldBe
      ujson.Obj("type" -> "tool", "toolCallId" -> "c1", "content" -> "r")
  }

  it should "write any other message's parts" in {
    val json = ujson.read(write[Message](UserMessage(Vector(ContentPart.Text("a"), image))))
    json("type") shouldBe ujson.Str("user")
    json.obj.contains("content") shouldBe false
    json("parts").arr.size shouldBe 2
  }

  it should "round-trip messages with parts, and read the old string shape as one text part" in {
    val messages: Vector[Message] = Vector(
      UserMessage(Vector(ContentPart.Text("a"), image)),
      ToolMessage(Vector(pdf, ContentPart.Text("b")), "c1"),
      UserMessage(Vector(ContentPart.Text("x"), ContentPart.Text("y"))),
      UserMessage("plain")
    )
    read[Vector[Message]](write(messages)) shouldBe messages
    read[Message](ujson.Obj("type" -> "user", "content" -> "old")) shouldBe UserMessage("old")
    read[Message](ujson.Obj("type" -> "tool", "toolCallId" -> "c", "content" -> "old")) shouldBe
      ToolMessage("old", "c")
  }

  "The case-class codecs" should "read both shapes too" in {
    read[UserMessage](write(UserMessage(Vector(image)))) shouldBe UserMessage(Vector(image))
    read[UserMessage](ujson.Obj("content" -> "old")) shouldBe UserMessage("old")
    read[ToolMessage](write(ToolMessage(Vector(pdf), "c"))) shouldBe ToolMessage(Vector(pdf), "c")
    read[ToolMessage](ujson.Obj("content" -> "old", "toolCallId" -> "c")) shouldBe ToolMessage("old", "c")
  }
}
