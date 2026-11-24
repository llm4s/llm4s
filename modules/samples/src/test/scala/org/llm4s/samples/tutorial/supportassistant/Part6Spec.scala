package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.error.RateLimitError
import org.llm4s.samples.tutorial.supportassistant.solutions.Part6Solution
import org.llm4s.testkit.{ Reply, ScriptedLLMClient, UnscriptedCallError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Part 6 of the support-assistant tutorial (`docs/tutorials/support-assistant/part-6-testing.md`): its fake model and
 * its judged evaluation, run against scripted clients (the page's test, `SupportAgentSpec`, runs on its own); its
 * page, compared with the code word for word; and exercise 6, the solution and yours.
 */
@org.llm4s.tags.DocsSpec
class Part6Spec extends AnyFlatSpec with Matchers with TutorialSpecSupport {
  import Part6Testing.*

  "Part 6's fake model" should "answer from the order the tool returns" in {
    def answer(question: String) =
      Part5SafetyMemory.safeAgent(fakeModel()).flatMap(_.run(question)).map(_.answer)

    answer("Where is my order A-1001?") shouldBe Right(Some("Your order is shipped."))
    answer("Where is my order A-1002?") shouldBe Right(Some("Your order is processing."))
    answer("Where is my order Z-9?") shouldBe Right(Some("I cannot find that order. Please check the number."))
  }

  it should "refuse a message it has no rule for" in {
    val result = Part5SafetyMemory.safeAgent(fakeModel()).flatMap(_.run("Hello"))
    result.left.toOption.map(_.toString).get should include(classOf[UnscriptedCallError].getSimpleName)
  }

  "Part 6's evaluation" should "pass every answer the judge scores at 0.8 or more" in {
    val judge = ScriptedLLMClient.always(Reply.text("0.9"))

    val verdicts = evaluate(fakeModel(), judge, evalCases)

    verdicts.map(_.map(_.passed)) shouldBe Right(Seq(true, true, true))
    verdicts.map(_.map(_.answer)).toOption.get.head shouldBe "Your order is shipped."
    judge.callCount shouldBe 3
    val asked = judge.calls.head.lastUserText.get
    asked should include("Order A-1001 has shipped.")
    asked should include("Your order is shipped.")
  }

  it should "fail an answer the judge scores low, or cannot score" in {
    val judge = ScriptedLLMClient.sequence(Reply.text("0.3"), Reply.text("It looks fine"), Reply.text("1"))

    evaluate(fakeModel(), judge, evalCases).map(_.map(_.passed)) shouldBe Right(Seq(false, false, true))
  }

  it should "stop with the judge's error when the judge's provider fails" in {
    val judge = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")))

    evaluate(fakeModel(), judge, evalCases) shouldBe Left(RateLimitError("openai"))
  }

  it should "report every case in its demo" in {
    evaluate(fakeModel(), scriptedJudge(), evalCases).map(report).toOption.get should endWith("3 of 3 passed")
  }

  "Part 6's page" should "show the code of Part6Testing and SupportAgentSpec, in the same order, word for word" in {
    val code = regions("Part6Testing.scala")
    code should have size 2
    val spec = testRegions("SupportAgentSpec.scala")
    spec should have size 1
    pageBlocks("part-6-testing.md") shouldBe List(code.head, spec.head, code(1))
  }

  /** Exercise 6: refundModel calls refund_policy for a refund question, answers with the policy, and nothing else. */
  private def checkRefundModel(model: => ScriptedLLMClient): Unit = {
    val refund = model
    Part5SafetyMemory.safeAgent(refund).flatMap(_.run("Can I get a REFUND for A-1001?")).map(_.answer) shouldBe
      Right(Some(Part3Tools.policies("refund")))
    refund.callCount shouldBe 2
    refund.calls.head.options.tools.map(_.name) should contain("refund_policy")

    Part5SafetyMemory.safeAgent(model).flatMap(_.run("Where is my order A-1001?")).isLeft shouldBe true
  }

  "Exercise 6's solution" should "script the refund question" in {
    checkRefundModel(Part6Solution.refundModel())
  }

  "Your exercise 6 (Part6Exercise.refundModel)" should "script the refund question" in {
    yourExercise(checkRefundModel(Part6Exercise.refundModel()))
  }
}
