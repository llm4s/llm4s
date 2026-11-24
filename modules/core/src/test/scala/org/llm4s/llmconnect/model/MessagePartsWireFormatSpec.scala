package org.llm4s.llmconnect.model

import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * The stored JSON of a user and a tool message (#2809): one text part is written as `content`, any other parts as
 * `parts`, whatever `Seq` holds them. Pinned before the parts became a `Seq`, so the change could not move the format
 * that checkpoints, run queues and thinking-binding digests hold.
 */
class MessagePartsWireFormatSpec extends AnyFlatSpec with Matchers {

  private val image = ContentPart.Image(MediaSource.Url(java.net.URI.create("https://x/i.png")), MediaType.Png)

  "A user message" should "write one text part as content" in {
    write[Message](UserMessage("hi")) shouldBe """{"type":"user","content":"hi"}"""
    write[Message](UserMessage(Seq(ContentPart.Text("hi")))) shouldBe """{"type":"user","content":"hi"}"""
    write[Message](UserMessage(List(ContentPart.Text("hi")))) shouldBe """{"type":"user","content":"hi"}"""
  }

  it should "write several parts as parts, and read them back equal" in {
    val expected =
      """{"type":"user","parts":[{"type":"text","text":"see"},""" +
        """{"type":"image","mediaType":"image/png","source":{"type":"url","uri":"https://x/i.png"}}]}"""
    val vector = UserMessage(Vector(ContentPart.Text("see"), image))
    val list   = UserMessage(List(ContentPart.Text("see"), image))
    write[Message](vector) shouldBe expected
    write[Message](list) shouldBe expected
    read[Message](expected) shouldBe vector
    vector shouldBe list
    vector.hashCode shouldBe list.hashCode
  }

  "A tool message" should "write its id, then one text part as content or several as parts" in {
    write[Message](ToolMessage("done", ToolCallId("c1"))) shouldBe
      """{"type":"tool","toolCallId":"c1","content":"done"}"""
    write[Message](ToolMessage(List(ContentPart.Text("done")), ToolCallId("c1"))) shouldBe
      """{"type":"tool","toolCallId":"c1","content":"done"}"""
    val expected =
      """{"type":"tool","toolCallId":"c1","parts":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}"""
    write[Message](ToolMessage(List(ContentPart.Text("a"), ContentPart.Text("b")), ToolCallId("c1"))) shouldBe expected
    read[Message](expected) shouldBe ToolMessage(Vector(ContentPart.Text("a"), ContentPart.Text("b")), ToolCallId("c1"))
  }
}
