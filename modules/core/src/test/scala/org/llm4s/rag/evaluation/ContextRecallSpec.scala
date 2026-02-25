package org.llm4s.rag.evaluation

import org.llm4s.rag.evaluation.metrics.ContextRecall
import org.llm4s.testutil.MockLLMClients
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ContextRecallSpec extends AnyFlatSpec with Matchers {

  "ContextRecall" should "have correct metadata" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = ContextRecall(mockClient)

    metric.name shouldBe "context_recall"
    metric.description should include("relevant information")
    metric.requiredInputs should contain(RequiredInput.Contexts)
    metric.requiredInputs should contain(RequiredInput.GroundTruth)
    metric.requiredInputs should not contain RequiredInput.Question // Not required
  }

  it should "return score 1.0 when all facts are covered" in {
    val factsResponse       = """["Paris is the capital", "Paris is in France"]"""
    val attributionResponse = """[
      {"fact": "Paris is the capital", "covered": true, "source": 1},
      {"fact": "Paris is in France", "covered": true, "source": 1}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse, attributionResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris.",
      contexts = Seq("Paris is the capital of France."),
      groundTruth = Some("Paris is the capital of France and is located in France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "return score 0.5 when half the facts are covered" in {
    val factsResponse       = """["Paris is the capital", "Paris has 2 million people"]"""
    val attributionResponse = """[
      {"fact": "Paris is the capital", "covered": true, "source": 1},
      {"fact": "Paris has 2 million people", "covered": false, "source": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse, attributionResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "Tell me about Paris",
      answer = "Paris is the capital.",
      contexts = Seq("Paris is the capital of France."),
      groundTruth = Some("Paris is the capital of France and has 2 million people.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.5
  }

  it should "return score 0.0 when no facts are covered" in {
    val factsResponse       = """["Tokyo is the capital of Japan"]"""
    val attributionResponse = """[
      {"fact": "Tokyo is the capital of Japan", "covered": false, "source": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse, attributionResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "What is the capital of Japan?",
      answer = "Unknown.",
      contexts = Seq("Paris is the capital of France."), // Wrong context
      groundTruth = Some("Tokyo is the capital of Japan.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "return score 1.0 when no facts in ground truth" in {
    val factsResponse = """[]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "How are you?",
      answer = "Fine.",
      contexts = Seq("Some context"),
      groundTruth = Some("I am doing well.") // No factual claims
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "fail when ground truth is missing" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("Paris is the capital."),
      groundTruth = None
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("ground_truth")
  }

  it should "return score 1.0 when ground truth is empty" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq("Context"),
      groundTruth = Some("   ") // whitespace only
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "return score 0.0 when no contexts provided" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq.empty,
      groundTruth = Some("Some ground truth.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "handle JSON response wrapped in markdown code blocks" in {
    val factsResponse       = """```json
["Fact 1"]
```"""
    val attributionResponse = """```json
[{"fact": "Fact 1", "covered": true, "source": 1}]
```"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse, attributionResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq("Context with fact 1"),
      groundTruth = Some("Fact 1 is true.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "include details about facts in result" in {
    val factsResponse       = """["Fact 1", "Fact 2", "Fact 3"]"""
    val attributionResponse = """[
      {"fact": "Fact 1", "covered": true, "source": 1},
      {"fact": "Fact 2", "covered": true, "source": 1},
      {"fact": "Fact 3", "covered": false, "source": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(factsResponse, attributionResponse))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer",
      contexts = Seq("Context"),
      groundTruth = Some("Fact 1. Fact 2. Fact 3.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    val details = result.toOption.get.details
    details.get("totalFacts") shouldBe Some(3)
    details.get("coveredFacts") shouldBe Some(2)
    details.get("missingFacts").map(_.asInstanceOf[Seq[String]]) shouldBe Some(Seq("Fact 3"))
  }

  it should "propagate LLM client errors" in {
    val mockClient = new MockLLMClients.FailingMock("LLM service unavailable")
    val metric     = ContextRecall(mockClient)

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
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("not valid json at all"))
    val metric     = ContextRecall(mockClient)

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
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = ContextRecall(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris.",
      contexts = Seq("   ", "  \t  "),
      groundTruth = Some("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("No contexts provided to check coverage against")
  }
}
