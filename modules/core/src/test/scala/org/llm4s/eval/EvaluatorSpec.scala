package org.llm4s.eval

import org.llm4s.eval.metrics._
import org.llm4s.testutil.{ FailingMockLLMClient, MockLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EvaluatorSpec extends AnyFlatSpec with Matchers {

  val goodResponse = "SCORE: 0.85\nEXPLANATION: Test"

  "Evaluator.evaluate" should "return EvalResult on success" in {
    val result =
      new Evaluator(new MockLLMClient(goodResponse)).evaluate(Faithfulness.unsafe(), EvalContext("Q", Seq("C"), "A"))
    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.85
  }

  it should "propagate errors from metric" in {
    new Evaluator(new FailingMockLLMClient())
      .evaluate(Faithfulness.unsafe(), EvalContext("Q", Seq("C"), "A"))
      .isLeft shouldBe true
  }

  "Evaluator.evaluateAll" should "evaluate all metrics" in {
    val result = new Evaluator(new MockLLMClient(goodResponse))
      .evaluateAll(Seq(Faithfulness.unsafe(), AnswerRelevance.unsafe()), EvalContext("Q", Seq("C"), "A"))
    result.isRight shouldBe true
    result.toOption.get should have size 2
  }

  it should "fail on first error" in {
    new Evaluator(new FailingMockLLMClient())
      .evaluateAll(Seq(Faithfulness.unsafe(), AnswerRelevance.unsafe()), EvalContext("Q", Seq("C"), "A"))
      .isLeft shouldBe true
  }

  "Evaluator.apply" should "create an evaluator" in {
    val client    = new MockLLMClient(goodResponse)
    val evaluator = Evaluator(client)
    evaluator shouldBe a[Evaluator]
    evaluator.llmClient shouldBe client
  }
}
