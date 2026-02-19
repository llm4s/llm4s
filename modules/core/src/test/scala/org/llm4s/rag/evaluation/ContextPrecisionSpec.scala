package org.llm4s.rag.evaluation

import org.llm4s.rag.evaluation.metrics.ContextPrecision
import org.llm4s.testutil.MockLLMClients
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ContextPrecisionSpec extends AnyFlatSpec with Matchers {

  "ContextPrecision" should "have correct metadata" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = ContextPrecision(mockClient)

    metric.name shouldBe "context_precision"
    metric.description should include("ranked at the top")
    metric.requiredInputs should contain(RequiredInput.Question)
    metric.requiredInputs should contain(RequiredInput.Contexts)
    metric.requiredInputs should contain(RequiredInput.GroundTruth)
  }

  it should "return score 1.0 when relevant doc is at position 1" in {
    // Only one context, and it's relevant
    val relevanceResponse = """[{"index": 0, "relevant": true}]"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital of France.",
      contexts = Seq("Paris is the capital and largest city of France."),
      groundTruth = Some("The capital of France is Paris.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    // AP = 1/1 * 1 = 1.0
    result.toOption.get.score shouldBe 1.0
  }

  it should "return score 1.0 when all relevant docs are at top positions" in {
    // First 2 relevant, last 2 not relevant
    val relevanceResponse = """[
      {"index": 0, "relevant": true},
      {"index": 1, "relevant": true},
      {"index": 2, "relevant": false},
      {"index": 3, "relevant": false}
    ]"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital.",
      contexts = Seq("Paris is the capital.", "Paris is in France.", "Weather today.", "Random info."),
      groundTruth = Some("The capital of France is Paris.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    // AP = (1/1 + 2/2) / 2 = (1 + 1) / 2 = 1.0
    result.toOption.get.score shouldBe 1.0
  }

  it should "return lower score when relevant docs are at lower positions" in {
    // Relevant docs at positions 2 and 4 (indices 1 and 3)
    val relevanceResponse = """[
      {"index": 0, "relevant": false},
      {"index": 1, "relevant": true},
      {"index": 2, "relevant": false},
      {"index": 3, "relevant": true}
    ]"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Irrelevant 1", "Relevant 1", "Irrelevant 2", "Relevant 2"),
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    // At index 1: precision@2 = 1/2 = 0.5
    // At index 3: precision@4 = 2/4 = 0.5
    // AP = (0.5 + 0.5) / 2 = 0.5
    result.toOption.get.score shouldBe 0.5
  }

  it should "return score 0.0 when no contexts are relevant" in {
    val relevanceResponse = """[
      {"index": 0, "relevant": false},
      {"index": 1, "relevant": false}
    ]"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris.",
      contexts = Seq("Weather info", "Sports news"),
      groundTruth = Some("Paris is the capital of France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "fail when ground truth is missing" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris.",
      contexts = Seq("Paris is the capital of France."),
      groundTruth = None
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("ground_truth")
  }

  it should "return score 0.0 when ground truth is empty" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris.",
      contexts = Seq("Paris is the capital."),
      groundTruth = Some("   ") // whitespace only
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "return score 0.0 when no contexts provided" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris.",
      contexts = Seq.empty,
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "handle JSON response wrapped in markdown code blocks" in {
    val relevanceResponse = """```json
[{"index": 0, "relevant": true}]
```"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq("Context"),
      groundTruth = Some("Ground truth")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "include details about relevance per position in result" in {
    val relevanceResponse = """[
      {"index": 0, "relevant": true},
      {"index": 1, "relevant": false}
    ]"""

    val mockClient = new MockLLMClients.SimpleMock(relevanceResponse)
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq("Relevant context", "Irrelevant context"),
      groundTruth = Some("Ground truth")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    val details = result.toOption.get.details
    details.get("relevantCount") shouldBe Some(1)
    details.get("totalContexts") shouldBe Some(2)
  }

  it should "propagate LLM client errors" in {
    val mockClient = new MockLLMClients.FailingMock("LLM service unavailable")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Some context"),
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("LLM service unavailable")
  }

  it should "return error on malformed JSON response" in {
    val mockClient = new MockLLMClients.SimpleMock("not valid json at all")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Some context"),
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
  }

  it should "treat whitespace-only contexts as empty" in {
    val mockClient = new MockLLMClients.SimpleMock("[]")
    val metric     = ContextPrecision(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("   ", "  \t  "),
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("No contexts to evaluate")
  }
}
