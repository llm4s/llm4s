package org.llm4s.llmconnect.provider

import com.openai.core.ObjectMappers
import com.openai.models.responses.ResponseOutputItem
import org.llm4s.llmconnect.model.ThinkingBlock
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

final class OpenAIReasoningItemsSpec extends AnyFlatSpec with Matchers:
  import OpenAIResponsesFixtures.{ functionCall, reasoningItem, message => outputMessage }

  private def output(items: String*): Seq[ResponseOutputItem] =
    ObjectMappers
      .jsonMapper()
      .readValue(
        s"[${items.mkString(",")}]",
        new com.fasterxml.jackson.core.`type`.TypeReference[java.util.List[ResponseOutputItem]] {}
      )
      .asScala
      .toSeq

  private def opaque(blocks: Seq[ThinkingBlock]) =
    blocks.collect { case ThinkingBlock.Opaque(p, d) => (p, ujson.read(d)) }

  "OpenAIReasoningItems.blocks" should "hold each encrypted reasoning item as an openai opaque block anchored to the call after it" in {
    val blocks = OpenAIReasoningItems.blocks(
      output(
        reasoningItem("rs_1"),
        functionCall("call_1", "get_weather", "{}"),
        reasoningItem("rs_2"),
        functionCall("call_2", "get_time", "{}")
      )
    )
    val found = opaque(blocks)
    found.map(_._1) shouldBe Seq(ProviderId("openai"), ProviderId("openai"))
    found.map(_._2("before").str) shouldBe Seq("call_1", "call_2")
    found.map(_._2("item")("id").str) shouldBe Seq("rs_1", "rs_2")
    found.map(_._2("item")("encrypted_content").str) shouldBe Seq("enc-rs_1", "enc-rs_2")
  }

  it should "leave out `before` when the next item is the message or nothing" in {
    val withMessage = opaque(OpenAIReasoningItems.blocks(output(reasoningItem("rs_1"), outputMessage("hi"))))
    val alone       = opaque(OpenAIReasoningItems.blocks(output(reasoningItem("rs_1"))))
    withMessage.map(_._2.obj.keySet) shouldBe Seq(Set("item"))
    alone.map(_._2.obj.keySet) shouldBe Seq(Set("item"))
  }

  it should "anchor reasoning items to the call after the run of reasoning items they start" in {
    val found = opaque(
      OpenAIReasoningItems.blocks(
        output(reasoningItem("rs_1"), reasoningItem("rs_2"), functionCall("call_1", "get_weather", "{}"))
      )
    )
    found.map(_._2("before").str) shouldBe Seq("call_1", "call_1")
    val toText = opaque(
      OpenAIReasoningItems.blocks(output(reasoningItem("rs_1"), reasoningItem("rs_2"), outputMessage("hi")))
    )
    toText.map(_._2.obj.keySet) shouldBe Seq(Set("item"), Set("item"))
  }

  it should "anchor each run of reasoning items to the call that ends it" in {
    val found = opaque(
      OpenAIReasoningItems.blocks(
        output(
          reasoningItem("rs_1"),
          reasoningItem("rs_2"),
          functionCall("call_1", "get_weather", "{}"),
          reasoningItem("rs_3"),
          functionCall("call_2", "get_time", "{}")
        )
      )
    )
    found.map(_._2("before").str) shouldBe Seq("call_1", "call_1", "call_2")
  }

  it should "leave a reasoning item unanchored when an item that is not a call follows it" in {
    val search = """{"type":"web_search_call","id":"ws_1","status":"completed"}"""
    val found = opaque(
      OpenAIReasoningItems.blocks(output(reasoningItem("rs_1"), search, functionCall("call_1", "get_weather", "{}")))
    )
    found.map(_._2.obj.keySet) shouldBe Seq(Set("item"))
  }

  it should "keep a summary as text, before its opaque block" in {
    val blocks = OpenAIReasoningItems.blocks(output(reasoningItem("rs_1", summary = Seq("First.", "Second."))))
    blocks.head shouldBe ThinkingBlock.Text("First.\n\nSecond.")
    blocks(1) shouldBe a[ThinkingBlock.Opaque]
  }

  it should "not store a reasoning item that has no encrypted content, but keep its summary" in {
    OpenAIReasoningItems.blocks(
      output(reasoningItem("rs_1", encrypted = None, summary = Seq("Thought.")))
    ) shouldBe Seq(ThinkingBlock.Text("Thought."))
    OpenAIReasoningItems.blocks(output(reasoningItem("rs_1", encrypted = None))) shouldBe empty
  }

  "OpenAIReasoningItems.stored" should "read back what blocks wrote, in order" in {
    val blocks = OpenAIReasoningItems.blocks(
      output(
        reasoningItem("rs_1", summary = Seq("a")),
        functionCall("call_1", "get_weather", "{}"),
        reasoningItem("rs_2"),
        functionCall("call_2", "get_time", "{}")
      )
    )
    OpenAIReasoningItems.stored(blocks).map(s => (s.before, s.item.id())) shouldBe
      Seq((Some("call_1"), "rs_1"), (Some("call_2"), "rs_2"))
  }

  it should "leave out another provider's opaque block, text blocks, and an openai block it cannot read" in {
    val openai = ProviderId("openai")
    OpenAIReasoningItems.stored(
      Seq(
        ThinkingBlock.Opaque(ProviderId("gemini"), "{}"),
        ThinkingBlock.Text("x"),
        ThinkingBlock.Opaque(openai, "not json"),
        ThinkingBlock.Opaque(openai, """{"item":{"type":"message"}}"""),
        ThinkingBlock.Opaque(openai, "{}")
      )
    ) shouldBe empty
  }

  it should "round-trip an item field for field" in {
    val blocks = OpenAIReasoningItems.blocks(output(reasoningItem("rs_1", summary = Seq("a"))))
    val item   = OpenAIReasoningItems.stored(blocks).head.item
    item.encryptedContent().get() shouldBe "enc-rs_1"
    item.summary().asScala.map(_.text()) shouldBe Seq("a")
  }

  it should "read an item whose status this SDK version does not know" in {
    val envelope = ujson.Obj(
      "item" -> ujson.read(
        """{"type":"reasoning","id":"rs_f","summary":[],"encrypted_content":"enc-f","status":"some_future_status"}"""
      )
    )
    val read = OpenAIReasoningItems.stored(Seq(ThinkingBlock.Opaque(ProviderId("openai"), envelope.render())))
    read.map(_.item.id()) shouldBe Seq("rs_f")
  }

  it should "give back an encrypted_content with awkward characters exactly" in {
    val awkward = ("A/b+c=" * 200) + "é中\"\\\n\t\u0001"
    val blocks  = OpenAIReasoningItems.blocks(output(reasoningItem("rs_1", encrypted = Some(awkward))))
    OpenAIReasoningItems.stored(blocks).map(_.item.encryptedContent().get()) shouldBe Seq(awkward)
  }
