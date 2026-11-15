package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.samples.tutorial.supportassistant.solutions.Part3Solution
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Part 3 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-3-tools.md`): its tools and agent,
 * run against a scripted client; its page, compared with the code word for word; and exercise 3, the solution and
 * yours.
 */
class Part3Spec extends AnyFlatSpec with Matchers with TutorialSpecSupport {
  import Part3Tools.*

  "Part 3's order tool" should "return a known order's status and total" in {
    val result = orderTool.toOption.get.execute(ujson.Obj("orderId" -> "A-1001"))
    result.map(json => (json("status").str, json("total").num)) shouldBe Right(("shipped", 42.5))
  }

  it should "tell the model when it does not know the order" in {
    val error = orderTool.toOption.get.execute(ujson.Obj("orderId" -> "Z-9"))
    error.left.map(_.getMessage).left.toOption.get should include("There is no order Z-9")
  }

  "Part 3's policy tool" should "answer for a topic in any case" in {
    val result = policyTool.toOption.get.execute(ujson.Obj("topic" -> "Refund"))
    result.map(_("text").str) shouldBe Right(policies("refund"))
  }

  "Part 3's agent" should "look up the order and the policy, then answer" in {
    val client = scripted()

    answer(client, question) shouldBe Right(
      "Order A-1001 has shipped. A refund goes back to your original payment method within 5 working days."
    )

    client.assertExhausted() shouldBe Right(())
    val toolOutputs = client.calls.last.messages.filter(_.role == MessageRole.Tool).map(_.content)
    toolOutputs should have size 2
    toolOutputs.head should include("shipped")
    toolOutputs(1) should include("5 working days")
  }

  it should "send both tools to the model" in {
    val client = scripted()
    answer(client, question).isRight shouldBe true
    client.calls.head.options.tools.map(_.name).toSet shouldBe Set("lookup_order", "refund_policy")
  }

  "Part 3's page" should "show the code of Part3Tools, in the same order, word for word" in {
    val code = regions("Part3Tools.scala")
    code should have size 3
    pageBlocks("part-3-tools.md") shouldBe code
  }

  /** Exercise 3: delivery_estimate answers 2 days for a shipped order, 4 for one processing, an error otherwise. */
  private def checkDeliveryTool(tool: => Result[ToolFunction[DeliveryQuery, DeliveryEstimate]]): Unit = {
    val built = tool.fold(e => fail(e.message), identity)
    built.name shouldBe "delivery_estimate"
    built.execute(ujson.Obj("orderId" -> "A-1001")).map(_("workingDays").num) shouldBe Right(2.0)
    built.execute(ujson.Obj("orderId" -> "A-1002")).map(_("workingDays").num) shouldBe Right(4.0)
    built.execute(ujson.Obj("orderId" -> "Z-9")).left.map(_.getMessage).left.toOption.get should include(
      "There is no order Z-9"
    )
  }

  "Exercise 3's solution" should "estimate delivery from the order's status" in {
    checkDeliveryTool(Part3Solution.deliveryTool)
  }

  "Your exercise 3 (Part3Exercise.deliveryTool)" should "estimate delivery from the order's status" in {
    yourExercise(checkDeliveryTool(Part3Exercise.deliveryTool))
  }
}
