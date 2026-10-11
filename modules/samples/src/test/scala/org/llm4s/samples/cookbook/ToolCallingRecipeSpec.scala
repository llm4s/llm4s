package org.llm4s.samples.cookbook

import org.llm4s.agent.AgentStatus
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolCall }
import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the two-tool recipe against its scripted client, with no network and no key. */
class ToolCallingRecipeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  "ToolCallingRecipe.run" should "run both tools the model asked for and answer from what they returned" in {
    val result = ToolCallingRecipe.run(ToolCallingRecipe.script, ToolCallingRecipe.question).value

    result.status shouldBe a[AgentStatus.Completed]
    result.answer.value should include("110.4")
    // both tools really ran: their outputs are tool messages in the thread, the rate first
    val toolOutputs = result.messages.filter(_.role == MessageRole.Tool).map(m => ujson.read(m.content))
    toolOutputs should have size 2
    toolOutputs(0)("rate").num shouldBe 0.92
    toolOutputs(1)("formatted").str shouldBe "110.4"
  }

  it should "offer the model both tools, and show it each tool's output before the next call" in {
    val client = ToolCallingRecipe.script

    ToolCallingRecipe.run(client, ToolCallingRecipe.question).value

    client.calls should have size 3
    (client.calls.head._2.tools.map(_.name) should contain).allOf("exchange_rate", "calculator")
    client.calls.map(_._1.messages.count(_.role == MessageRole.Tool)) shouldBe Vector(0, 1, 2)
  }

  it should "give the model the exchange-rate tool's error, rather than fail, for a pair it has no rate for" in {
    val client = new ScriptedClient((conversation, _) =>
      Right(conversation.messages.filter(_.role == MessageRole.Tool).lastOption match {
        case None =>
          AssistantMessage(None, Seq(ToolCall("c1", "exchange_rate", ujson.Obj("from" -> "USD", "to" -> "JPY"))))
        case Some(output) => AssistantMessage(s"I could not convert: ${output.content}")
      })
    )

    val result = ToolCallingRecipe.run(client, "How much is 5 USD in yen?").value

    result.answer.value should include("no rate from USD to JPY")
  }

  "ToolCallingRecipe.exchangeRate" should "read the currency codes in any case" in {
    val tool = ToolCallingRecipe.exchangeRate.value
    tool.handler(SafeParameterExtractor(ujson.Obj("from" -> "usd", "to" -> "eur"))).value shouldBe
      ExchangeRate("USD", "EUR", 0.92)
  }

  "ToolCallingRecipe.demo" should "print the answer" in {
    ToolCallingRecipe.demo(ToolCallingRecipe.script).value should include("110.4 euros")
  }
}
