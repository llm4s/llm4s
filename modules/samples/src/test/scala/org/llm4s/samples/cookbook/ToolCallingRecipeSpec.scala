package org.llm4s.samples.cookbook

import org.llm4s.agent.AgentStatus
import org.llm4s.llmconnect.model.MessageRole
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the tool-calling recipe against its scripted client, with no network and no key. */
class ToolCallingRecipeSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  "ToolCallingRecipe.run" should "run the calculator the model asked for and answer from what it returned" in {
    val client = ToolCallingRecipe.script

    val result = ToolCallingRecipe.run(client, ToolCallingRecipe.question).value

    result.status shouldBe a[AgentStatus.Completed]
    result.answer.value should include("127.5")
    // the tool really ran: its output is a tool message in the thread, and 15% of 850 is 127.5
    val toolMessages = result.messages.filter(_.role == MessageRole.Tool)
    toolMessages should have size 1
    toolMessages.head.content should include("127.5")
  }

  it should "offer the model the calculator on the first call, and show it the tool's output on the second" in {
    val client = ToolCallingRecipe.script

    ToolCallingRecipe.run(client, ToolCallingRecipe.question).value

    client.calls should have size 2
    val (firstConversation, firstOptions) = client.calls.head
    firstOptions.tools.map(_.name) should contain("calculator")
    firstConversation.messages.filter(_.role == MessageRole.Tool) shouldBe empty
    client.calls(1)._1.messages.filter(_.role == MessageRole.Tool) should have size 1
  }

  it should "print the answer in the demo" in {
    ToolCallingRecipe.demo(ToolCallingRecipe.script).value should include("127.5")
  }
}
