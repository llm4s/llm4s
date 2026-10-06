package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolResultPairingSpec extends AnyFlatSpec with Matchers {

  private def call(id: String) = ToolCall(id, "weather", ujson.Obj())

  "ToolResultPairing" should "pair calls with the run of tool messages straight after them" in {
    val messages = Seq(
      UserMessage("weather?"),
      AssistantMessage("", Seq(call("a"), call("b"))),
      ToolMessage("sunny", "a"),
      ToolMessage("rainy", "b"),
      AssistantMessage("Done.")
    )
    val pairing = ToolResultPairing.of(messages)
    pairing.callPaired(1, "a") shouldBe true
    pairing.callPaired(1, "b") shouldBe true
    pairing.resultPaired(2) shouldBe true
    pairing.resultPaired(3) shouldBe true
  }

  it should "not pair a result that a user message separates from its call" in {
    val messages = Seq(
      UserMessage("weather?"),
      AssistantMessage("", Seq(call("a"))),
      UserMessage("still there?"),
      ToolMessage("sunny", "a")
    )
    val pairing = ToolResultPairing.of(messages)
    pairing.callPaired(1, "a") shouldBe false
    pairing.resultPaired(3) shouldBe false
  }

  it should "not pair a result with a call of an earlier turn" in {
    val messages = Seq(
      AssistantMessage("", Seq(call("a"))),
      ToolMessage("sunny", "a"),
      AssistantMessage("", Seq(call("b"))),
      ToolMessage("late", "a"),
      ToolMessage("rainy", "b")
    )
    val pairing = ToolResultPairing.of(messages)
    pairing.resultPaired(1) shouldBe true
    pairing.resultPaired(3) shouldBe false
    pairing.resultPaired(4) shouldBe true
    pairing.callPaired(2, "b") shouldBe true
    pairing.callPaired(2, "a") shouldBe false
  }

  it should "pair only the first of two results for one call, and leave unanswered calls unpaired" in {
    val messages = Seq(
      AssistantMessage("", Seq(call("a"), call("b"))),
      ToolMessage("first", "a"),
      ToolMessage("second", "a")
    )
    val pairing = ToolResultPairing.of(messages)
    pairing.resultPaired(1) shouldBe true
    pairing.resultPaired(2) shouldBe false
    pairing.callPaired(0, "a") shouldBe true
    pairing.callPaired(0, "b") shouldBe false
  }

  it should "let a system message sit inside a run, since it is lifted out of the turns" in {
    val messages = Seq(
      AssistantMessage("", Seq(call("a"), call("b"))),
      ToolMessage("sunny", "a"),
      SystemMessage("be brief"),
      ToolMessage("rainy", "b")
    )
    val pairing = ToolResultPairing.of(messages)
    pairing.resultPaired(1) shouldBe true
    pairing.resultPaired(3) shouldBe true
  }
}
