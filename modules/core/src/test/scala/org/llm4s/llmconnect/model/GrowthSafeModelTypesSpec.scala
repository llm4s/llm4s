package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * `Conversation`, `SystemMessage`, `ToolCall` and `ResponseFormat.JsonSchema` are growth-prone (#2378): built
 * through `apply`, changed through `with*`, with a private constructor and `copy`.
 */
class GrowthSafeModelTypesSpec extends AnyFlatSpec with Matchers {

  "Conversation" should "replace its messages through withMessages" in {
    val conversation = Conversation(Seq(SystemMessage("s")))
    val replaced     = conversation.withMessages(Seq(UserMessage("u")))

    replaced shouldBe Conversation(Seq(UserMessage("u")))
    conversation.messages shouldBe Seq(SystemMessage("s"))
  }

  it should "keep its JSON" in {
    val conversation = Conversation(Seq(SystemMessage("s"), UserMessage("u")))
    read[Conversation](write(conversation)) shouldBe conversation
    ujson.read(write(Conversation(Seq(SystemMessage("s"))))).obj.keySet shouldBe Set("messages")
  }

  it should "still match positionally" in {
    (Conversation(Seq(SystemMessage("s"))) match { case Conversation(ms) => ms.size }) shouldBe 1
  }

  "SystemMessage" should "change its content through withContent" in {
    SystemMessage("a").withContent("b") shouldBe SystemMessage("b")
    SystemMessage("a").withContent("b").role shouldBe MessageRole.System
  }

  it should "keep its JSON" in {
    write(SystemMessage("hi")) shouldBe """{"$type":"SystemMessage","content":"hi"}"""
    read[SystemMessage]("""{"$type":"SystemMessage","content":"hi"}""") shouldBe SystemMessage("hi")
  }

  "ToolCall" should "change each field through its setter" in {
    val call    = ToolCall("1", "weather", ujson.Obj("city" -> "Paris"))
    val changed = call.withId("2").withName("time").withArguments(ujson.Obj("tz" -> "UTC"))

    changed shouldBe ToolCall("2", "time", ujson.Obj("tz" -> "UTC"))
    call.id shouldBe "1"
  }

  it should "keep its JSON" in {
    val call = ToolCall("1", "weather", ujson.Obj("city" -> "Paris"))
    write(call) shouldBe """{"id":"1","name":"weather","arguments":{"city":"Paris"}}"""
    read[ToolCall](write(call)) shouldBe call
  }

  "ResponseFormat.JsonSchema" should "default its name and strictness" in {
    val format = ResponseFormat.JsonSchema(ujson.Obj("type" -> "object"))
    format.name shouldBe "response"
    format.strict shouldBe true
  }

  it should "change each field through its setter" in {
    val format = ResponseFormat
      .JsonSchema(ujson.Obj())
      .withSchema(ujson.Obj("type" -> "string"))
      .withName("answer")
      .withStrict(false)

    format shouldBe ResponseFormat.JsonSchema(ujson.Obj("type" -> "string"), "answer", strict = false)
  }

  "The growth-prone model types" should "not expose their constructor or copy" in {
    assertTypeError("""new Conversation(Seq.empty)""")
    assertTypeError("""Conversation(Seq.empty).copy(messages = Seq.empty)""")
    assertTypeError("""new SystemMessage("s")""")
    assertTypeError("""SystemMessage("s").copy(content = "t")""")
    assertTypeError("""new ToolCall("1", "n", ujson.Obj())""")
    assertTypeError("""ToolCall("1", "n", ujson.Obj()).copy(id = "2")""")
    assertTypeError("""new ResponseFormat.JsonSchema(ujson.Obj(), "n", true)""")
    assertTypeError("""ResponseFormat.JsonSchema(ujson.Obj()).copy(strict = false)""")
  }
}
