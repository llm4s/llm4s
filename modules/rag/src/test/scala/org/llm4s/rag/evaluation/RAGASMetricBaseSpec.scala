package org.llm4s.rag.evaluation

import org.llm4s.rag.evaluation.metrics.{ ContextPrecision, Faithfulness }
import org.llm4s.testutil.MockLLMClients
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGASMetricBaseSpec extends AnyFlatSpec with Matchers {

  // Use Faithfulness (requires Question, Answer, Contexts) for canEvaluate tests
  private def faithfulnessMetric =
    Faithfulness(new MockLLMClients.SimpleMock("[]"))

  // Use ContextPrecision (requires Question, Contexts, GroundTruth) for comparison
  private def contextPrecisionMetric =
    ContextPrecision(new MockLLMClients.SimpleMock("[]"))

  "canEvaluate" should "return true when all required inputs are present" in {
    val metric = faithfulnessMetric
    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Paris is the capital.")
    )

    metric.canEvaluate(sample) shouldBe true
  }

  it should "return false when contexts are empty for a metric requiring them" in {
    val metric = faithfulnessMetric
    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq.empty
    )

    metric.canEvaluate(sample) shouldBe false
  }

  it should "return false when ground truth is missing for a metric requiring it" in {
    val metric = contextPrecisionMetric
    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Some context"),
      groundTruth = None
    )

    metric.canEvaluate(sample) shouldBe false
  }

  it should "return true when ground truth is present for a metric requiring it" in {
    val metric = contextPrecisionMetric
    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Some context"),
      groundTruth = Some("Paris is the capital.")
    )

    metric.canEvaluate(sample) shouldBe true
  }

  "evaluateBatch" should "evaluate multiple samples successfully" in {
    val claimsResponse       = """["single claim"]"""
    val verificationResponse = """[{"claim": "single claim", "supported": true, "evidence": "found"}]"""

    val mockClient = new MockLLMClients.MultiResponseMock(
      Seq(claimsResponse, verificationResponse, claimsResponse, verificationResponse)
    )
    val metric = Faithfulness(mockClient)

    val samples = Seq(
      EvalSample(
        question = "Q1",
        answer = "A1",
        contexts = Seq("Context 1")
      ),
      EvalSample(
        question = "Q2",
        answer = "A2",
        contexts = Seq("Context 2")
      )
    )

    val result = metric.evaluateBatch(samples)

    result.isRight shouldBe true
    result.toOption.get should have size 2
    result.toOption.get.foreach(_.metricName shouldBe "faithfulness")
  }

  it should "fail batch if any sample evaluation fails" in {
    val mockClient = new MockLLMClients.FailingMock("batch failure")
    val metric     = Faithfulness(mockClient)

    val samples = Seq(
      EvalSample(
        question = "Q1",
        answer = "A1",
        contexts = Seq("Context 1")
      ),
      EvalSample(
        question = "Q2",
        answer = "A2",
        contexts = Seq("Context 2")
      )
    )

    val result = metric.evaluateBatch(samples)

    result.isLeft shouldBe true
  }

  it should "return empty results for empty input" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = Faithfulness(mockClient)

    val result = metric.evaluateBatch(Seq.empty)

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }
}
