package org.llm4s.rag.evaluation

import org.llm4s.rag.evaluation.metrics.Faithfulness
import org.llm4s.testutil.MockLLMClients
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FaithfulnessSpec extends AnyFlatSpec with Matchers {

  "Faithfulness" should "have correct metadata" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = Faithfulness(mockClient)

    metric.name shouldBe "faithfulness"
    metric.description should include("supported by")
    metric.requiredInputs should contain(RequiredInput.Question)
    metric.requiredInputs should contain(RequiredInput.Answer)
    metric.requiredInputs should contain(RequiredInput.Contexts)
  }

  it should "return score 1.0 when all claims are supported" in {
    val claimsResponse       = """["Paris is the capital of France", "Paris has the Eiffel Tower"]"""
    val verificationResponse = """[
      {"claim": "Paris is the capital of France", "supported": true, "evidence": "Context states this"},
      {"claim": "Paris has the Eiffel Tower", "supported": true, "evidence": "Context mentions Eiffel Tower"}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse, verificationResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is special about Paris?",
      answer = "Paris is the capital of France and has the Eiffel Tower.",
      contexts = Seq("Paris is the capital and largest city of France. The Eiffel Tower is located in Paris.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
    result.toOption.get.metricName shouldBe "faithfulness"
  }

  it should "return score 0.5 when half claims are supported" in {
    val claimsResponse       = """["Paris is the capital of France", "Paris has a population of 10 million"]"""
    val verificationResponse = """[
      {"claim": "Paris is the capital of France", "supported": true, "evidence": "Context states this"},
      {"claim": "Paris has a population of 10 million", "supported": false, "evidence": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse, verificationResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What do you know about Paris?",
      answer = "Paris is the capital of France and has a population of 10 million.",
      contexts = Seq("Paris is the capital of France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.5
  }

  it should "return score 0.0 when no claims are supported" in {
    val claimsResponse       = """["London is the capital of England"]"""
    val verificationResponse = """[
      {"claim": "London is the capital of England", "supported": false, "evidence": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse, verificationResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital of England?",
      answer = "London is the capital of England.",
      contexts = Seq("Paris is the capital of France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
  }

  it should "return score 1.0 for empty answer (no claims to verify)" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "   ", // whitespace only
      contexts = Seq("Paris is the capital of France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
    result.toOption.get.details.get("reason") shouldBe Some("Empty answer has no claims to verify")
  }

  it should "return score 0.0 when no contexts provided" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital of France.",
      contexts = Seq.empty
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("No context provided to verify claims against")
  }

  it should "return score 1.0 when no claims extracted" in {
    val claimsResponse = """[]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "How are you?",
      answer = "I am fine, thank you!", // No factual claims
      contexts = Seq("Some context")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "handle JSON response wrapped in markdown code blocks" in {
    val claimsResponse       = """```json
["Paris is in France"]
```"""
    val verificationResponse = """```json
[{"claim": "Paris is in France", "supported": true, "evidence": "Context says so"}]
```"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse, verificationResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "Where is Paris?",
      answer = "Paris is in France.",
      contexts = Seq("Paris is a city in France.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 1.0
  }

  it should "reject invalid batch size" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))

    an[IllegalArgumentException] should be thrownBy {
      Faithfulness(mockClient, batchSize = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      Faithfulness(mockClient, batchSize = -1)
    }
  }

  it should "include details about claims in result" in {
    val claimsResponse       = """["Claim 1", "Claim 2"]"""
    val verificationResponse = """[
      {"claim": "Claim 1", "supported": true, "evidence": "Found in context"},
      {"claim": "Claim 2", "supported": false, "evidence": null}
    ]"""

    val mockClient = new MockLLMClients.MultiResponseMock(Seq(claimsResponse, verificationResponse))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "Question",
      answer = "Answer with claims",
      contexts = Seq("Some context")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    val details = result.toOption.get.details
    details.get("totalClaims") shouldBe Some(2)
    details.get("supportedClaims") shouldBe Some(1)
  }

  it should "propagate LLM client errors" in {
    val mockClient = new MockLLMClients.FailingMock("LLM service unavailable")
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris is the capital.",
      contexts = Seq("Some context")
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("LLM service unavailable")
  }

  it should "return error on malformed JSON response" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("not valid json at all"))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris is the capital.",
      contexts = Seq("Some context")
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
  }

  it should "treat whitespace-only contexts as empty" in {
    val mockClient = new MockLLMClients.MultiResponseMock(Seq("[]"))
    val metric     = Faithfulness(mockClient)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris is the capital.",
      contexts = Seq("   ", "  \t  ")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("No context provided to verify claims against")
  }
}
