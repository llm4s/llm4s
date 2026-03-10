package org.llm4s.rag.evaluation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGASTypesSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // EvalSample
  // ==========================================================================

  "EvalSample" should "create with required fields" in {
    val sample = EvalSample(
      question = "What is X?",
      answer = "X is Y.",
      contexts = Seq("Context about X")
    )
    sample.question shouldBe "What is X?"
    sample.answer shouldBe "X is Y."
    sample.contexts shouldBe Seq("Context about X")
    sample.groundTruth shouldBe None
    sample.metadata shouldBe Map.empty
  }

  it should "create with all fields" in {
    val sample = EvalSample(
      question = "What is X?",
      answer = "X is Y.",
      contexts = Seq("ctx1", "ctx2"),
      groundTruth = Some("X is indeed Y."),
      metadata = Map("source" -> "test")
    )
    sample.groundTruth shouldBe Some("X is indeed Y.")
    sample.metadata shouldBe Map("source" -> "test")
  }

  it should "allow empty contexts" in {
    val sample = EvalSample(question = "Q?", answer = "A.", contexts = Seq.empty)
    sample.contexts shouldBe empty
  }

  it should "reject empty question" in {
    an[IllegalArgumentException] should be thrownBy {
      EvalSample(question = "", answer = "A.", contexts = Seq.empty)
    }
  }

  it should "reject empty answer" in {
    an[IllegalArgumentException] should be thrownBy {
      EvalSample(question = "Q?", answer = "", contexts = Seq.empty)
    }
  }

  // ==========================================================================
  // MetricResult
  // ==========================================================================

  "MetricResult" should "create with valid score" in {
    val result = MetricResult("faithfulness", 0.85)
    result.metricName shouldBe "faithfulness"
    result.score shouldBe 0.85
    result.details shouldBe Map.empty
  }

  it should "accept score of 0.0" in {
    val result = MetricResult("test", 0.0)
    result.score shouldBe 0.0
  }

  it should "accept score of 1.0" in {
    val result = MetricResult("test", 1.0)
    result.score shouldBe 1.0
  }

  it should "reject score below 0.0" in {
    an[IllegalArgumentException] should be thrownBy {
      MetricResult("test", -0.1)
    }
  }

  it should "reject score above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      MetricResult("test", 1.1)
    }
  }

  it should "include details map" in {
    val result = MetricResult("test", 0.5, Map("claims" -> 3, "supported" -> 2))
    result.details("claims") shouldBe 3
    result.details("supported") shouldBe 2
  }

  // ==========================================================================
  // EvalResult
  // ==========================================================================

  "EvalResult" should "compute getMetric correctly" in {
    val sample = EvalSample("Q?", "A.", Seq("ctx"))
    val result = EvalResult(
      sample = sample,
      metrics = Seq(MetricResult("faithfulness", 0.8), MetricResult("answer_relevancy", 0.9)),
      ragasScore = 0.85
    )

    result.getMetric("faithfulness") shouldBe Some(MetricResult("faithfulness", 0.8))
    result.getMetric("answer_relevancy") shouldBe Some(MetricResult("answer_relevancy", 0.9))
    result.getMetric("nonexistent") shouldBe None
  }

  it should "check metricPassed with threshold" in {
    val sample = EvalSample("Q?", "A.", Seq("ctx"))
    val result = EvalResult(
      sample = sample,
      metrics = Seq(MetricResult("faithfulness", 0.8)),
      ragasScore = 0.8
    )

    result.metricPassed("faithfulness", 0.7) shouldBe true
    result.metricPassed("faithfulness", 0.8) shouldBe true
    result.metricPassed("faithfulness", 0.9) shouldBe false
    result.metricPassed("nonexistent", 0.5) shouldBe false
  }

  it should "handle empty metrics list" in {
    val sample = EvalSample("Q?", "A.", Seq("ctx"))
    val result = EvalResult(sample = sample, metrics = Seq.empty, ragasScore = 0.0)
    result.getMetric("faithfulness") shouldBe None
    result.metricPassed("faithfulness", 0.5) shouldBe false
  }

  // ==========================================================================
  // EvalSummary
  // ==========================================================================

  "EvalSummary" should "filter low-scoring samples by threshold" in {
    val sample1 = EvalSample("Q1?", "A1.", Seq("ctx"))
    val sample2 = EvalSample("Q2?", "A2.", Seq("ctx"))
    val result1 = EvalResult(sample1, Seq(MetricResult("f", 0.3)), 0.3)
    val result2 = EvalResult(sample2, Seq(MetricResult("f", 0.9)), 0.9)

    val summary = EvalSummary(
      results = Seq(result1, result2),
      averages = Map("f" -> 0.6),
      overallRagasScore = 0.6,
      sampleCount = 2
    )

    summary.lowScoringSamples(0.5) shouldBe Seq(result1)
    summary.lowScoringSamples(0.1) shouldBe empty
    summary.lowScoringSamples(1.0) shouldBe Seq(result1, result2)
  }

  it should "filter low-scoring samples by specific metric" in {
    val sample1 = EvalSample("Q1?", "A1.", Seq("ctx"))
    val sample2 = EvalSample("Q2?", "A2.", Seq("ctx"))
    val result1 = EvalResult(sample1, Seq(MetricResult("f", 0.3), MetricResult("ar", 0.9)), 0.6)
    val result2 = EvalResult(sample2, Seq(MetricResult("f", 0.9), MetricResult("ar", 0.4)), 0.65)

    val summary = EvalSummary(
      results = Seq(result1, result2),
      averages = Map("f" -> 0.6, "ar" -> 0.65),
      overallRagasScore = 0.625,
      sampleCount = 2
    )

    summary.lowScoringForMetric("f", 0.5) shouldBe Seq(result1)
    summary.lowScoringForMetric("ar", 0.5) shouldBe Seq(result2)
    summary.lowScoringForMetric("nonexistent", 0.5) shouldBe empty
  }

  it should "handle empty results" in {
    val summary = EvalSummary(Seq.empty, Map.empty, 0.0, 0)
    summary.lowScoringSamples(0.5) shouldBe empty
    summary.lowScoringForMetric("f", 0.5) shouldBe empty
  }

  // ==========================================================================
  // ClaimVerification
  // ==========================================================================

  "ClaimVerification" should "create with supported claim" in {
    val cv = ClaimVerification("Earth is round", supported = true, Some("Scientific evidence"))
    cv.claim shouldBe "Earth is round"
    cv.supported shouldBe true
    cv.evidence shouldBe Some("Scientific evidence")
  }

  it should "create with unsupported claim and no evidence" in {
    val cv = ClaimVerification("claim", supported = false)
    cv.supported shouldBe false
    cv.evidence shouldBe None
  }

  // ==========================================================================
  // EvaluationError
  // ==========================================================================

  "EvaluationError" should "create with message" in {
    val error = EvaluationError("Something went wrong")
    error.message shouldBe "Something went wrong"
    error.code shouldBe Some("EVALUATION_ERROR")
  }

  it should "create missing input error" in {
    val error = EvaluationError.missingInput("ground_truth")
    error.message shouldBe "Required input missing: ground_truth"
    error.code shouldBe Some("MISSING_INPUT")
  }

  it should "create parse error" in {
    val error = EvaluationError.parseError("invalid JSON")
    error.message shouldBe "Failed to parse LLM response: invalid JSON"
    error.code shouldBe Some("PARSE_ERROR")
  }

  // ==========================================================================
  // EvaluatorOptions
  // ==========================================================================

  "EvaluatorOptions" should "have sensible defaults" in {
    val opts = EvaluatorOptions()
    opts.parallelEvaluation shouldBe false
    opts.maxConcurrency shouldBe 4
    opts.timeoutMs shouldBe 30000
  }

  it should "allow customization" in {
    val opts = EvaluatorOptions(parallelEvaluation = true, maxConcurrency = 8, timeoutMs = 60000)
    opts.parallelEvaluation shouldBe true
    opts.maxConcurrency shouldBe 8
    opts.timeoutMs shouldBe 60000
  }
}
