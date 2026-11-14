package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.ResponseFormat
import org.llm4s.samples.tutorial.supportassistant.solutions.Part2Solution
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Part 2 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-2-typed-answers.md`): its code,
 * run against a scripted client; its page, compared with the code word for word; and exercise 2, the solution and
 * yours.
 */
class Part2Spec extends AnyFlatSpec with Matchers with TutorialSpecSupport {
  import Part2TypedAnswers.*

  "Part 2's classify" should "read the model's JSON into a Ticket" in {
    classify(scripted(), Part2TypedAnswers.message) shouldBe Right(
      Ticket(Category.Billing, 4, Some("A-1001"), "Charged twice for order A-1001.")
    )
  }

  it should "read a missing order number as None" in {
    val client = ScriptedLLMClient.sequence(
      Reply.text("""{"category": "Delivery", "urgency": 2, "orderId": null, "summary": "Parcel is late."}""")
    )
    classify(client, "My parcel is late.") shouldBe Right(Ticket(Category.Delivery, 2, None, "Parcel is late."))
  }

  it should "send the derived JSON schema with the request" in {
    val client = scripted()
    classify(client, Part2TypedAnswers.message).isRight shouldBe true
    val format = client.lastCall.flatMap(_.options.responseFormat)
    format.collect { case f: ResponseFormat.JsonSchema => f } shouldBe defined
  }

  it should "refuse a category the enum does not have" in {
    val client = ScriptedLLMClient.sequence(
      Reply.text("""{"category": "Spam", "urgency": 1, "orderId": null, "summary": "Buy now."}""")
    )
    classify(client, "Buy now!").isLeft shouldBe true
  }

  "Part 2's triage" should "refuse an urgency outside 1 to 5" in {
    val client = ScriptedLLMClient.sequence(
      Reply.text("""{"category": "Other", "urgency": 9, "orderId": null, "summary": "Hello."}""")
    )
    triage(client, "Hello").left.map(_.getClass) shouldBe Left(classOf[ValidationError])
  }

  it should "pass a valid ticket through" in {
    triage(scripted(), Part2TypedAnswers.message).map(_.urgency) shouldBe Right(4)
  }

  "Part 2's page" should "show the code of Part2TypedAnswers, in the same order, word for word" in {
    val code = regions("Part2TypedAnswers.scala")
    code should have size 3
    pageBlocks("part-2-typed-answers.md") shouldBe code
  }

  /** Exercise 2: a RefundRequest read with completeStructuredOf, and an amount that is not above 0 refused. */
  private def checkRefundRequest(refundRequest: (LLMClient, String) => Result[RefundRequest]): Unit = {
    val good = ScriptedLLMClient.sequence(
      Reply.text("""{"orderId": "A-1001", "amount": 42.5, "reason": "The jacket arrived torn."}""")
    )
    refundRequest(good, "Please refund the 42.50 for A-1001, the jacket arrived torn.") shouldBe Right(
      RefundRequest("A-1001", 42.5, "The jacket arrived torn.")
    )
    good.lastCall.flatMap(_.options.responseFormat).collect { case f: ResponseFormat.JsonSchema => f } shouldBe defined

    val zero = ScriptedLLMClient.sequence(Reply.text("""{"orderId": "A-1002", "amount": 0, "reason": "None given."}"""))
    refundRequest(zero, "Refund A-1002") match {
      case Left(error: ValidationError) => error.field shouldBe "amount"
      case other                        => fail(s"expected a ValidationError on amount, got $other")
    }
  }

  "Exercise 2's solution" should "extract a refund request and refuse an amount that is not above zero" in {
    checkRefundRequest(Part2Solution.refundRequest)
  }

  "Your exercise 2 (Part2Exercise.refundRequest)" should "extract a refund request and refuse a zero amount" in {
    yourExercise(checkRefundRequest(Part2Exercise.refundRequest))
  }
}
