package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.rag.evaluation.metrics.AnswerRelevancy
import org.llm4s.testutil.MockLLMClients
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnswerRelevancySpec extends AnyFlatSpec with Matchers {

  class MockEmbeddingProvider(embeddings: Map[String, Seq[Double]]) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val results = request.input.map { text =>
        embeddings.getOrElse(text, Seq.fill(3)(0.5)) // Default embedding
      }
      Right(EmbeddingResponse(embeddings = results))
    }
  }

  def createMockEmbeddingClient(embeddings: Map[String, Seq[Double]]): EmbeddingClient =
    new EmbeddingClient(new MockEmbeddingProvider(embeddings))

  private val testEmbeddingConfig = EmbeddingModelConfig("test-model", 3)

  "AnswerRelevancy" should "have correct metadata" in {
    val mockLLM       = new MockLLMClients.SimpleMock("[]")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    metric.name shouldBe "answer_relevancy"
    metric.description should include("addresses the question")
    metric.requiredInputs should contain(RequiredInput.Question)
    metric.requiredInputs should contain(RequiredInput.Answer)
    metric.requiredInputs should not contain RequiredInput.Contexts // Not required
  }

  it should "return high score when generated questions are similar to original" in {
    val questionsResponse =
      """["What is the capital of France?", "What city is the capital of France?", "Which city serves as France's capital?"]"""

    // Embeddings that are identical (perfect similarity)
    val originalEmbedding   = Seq(1.0, 0.0, 0.0)
    val generatedEmbeddings = Seq(1.0, 0.0, 0.0) // Same as original

    val mockLLM = new MockLLMClients.SimpleMock(questionsResponse)
    val mockEmbedding = createMockEmbeddingClient(
      Map(
        "What is the capital of France?"         -> originalEmbedding,
        "What is the capital of France?"         -> generatedEmbeddings,
        "What city is the capital of France?"    -> generatedEmbeddings,
        "Which city serves as France's capital?" -> generatedEmbeddings
      )
    )

    val metric = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital of France.",
      contexts = Seq("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    // With identical embeddings, cosine similarity is 1.0, normalized to 1.0
    result.toOption.get.score shouldBe 1.0
  }

  it should "return lower score when generated questions are dissimilar" in {
    val questionsResponse = """["What is the weather today?", "How tall is the building?", "What time is it?"]"""

    // Orthogonal embeddings (zero similarity, normalizes to 0.5)
    val originalEmbedding  = Seq(1.0, 0.0, 0.0)
    val differentEmbedding = Seq(0.0, 1.0, 0.0)

    val mockLLM = new MockLLMClients.SimpleMock(questionsResponse)
    val mockEmbedding = createMockEmbeddingClient(
      Map(
        "What is the capital of France?" -> originalEmbedding,
        "What is the weather today?"     -> differentEmbedding,
        "How tall is the building?"      -> differentEmbedding,
        "What time is it?"               -> differentEmbedding
      )
    )

    val metric = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "It's sunny outside today.",
      contexts = Seq("Weather information")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    // Orthogonal embeddings have cosine similarity 0, normalized to 0.5
    result.toOption.get.score shouldBe 0.5
  }

  it should "return score 0.0 for empty answer" in {
    val mockLLM       = new MockLLMClients.SimpleMock("[]")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "   ", // whitespace only
      contexts = Seq("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("Empty answer cannot be relevant")
  }

  it should "return score 0.0 for empty question" in {
    val mockLLM       = new MockLLMClients.SimpleMock("[]")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "   ", // whitespace only
      answer = "Paris is the capital of France.",
      contexts = Seq("Paris is the capital.")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.0
    result.toOption.get.details.get("reason") shouldBe Some("Empty question cannot be compared")
  }

  it should "handle JSON response wrapped in markdown code blocks" in {
    val questionsResponse = """```json
["Question 1", "Question 2", "Question 3"]
```"""

    val embedding = Seq(1.0, 0.0, 0.0)

    val mockLLM       = new MockLLMClients.SimpleMock(questionsResponse)
    val mockEmbedding = createMockEmbeddingClient(Map.empty.withDefaultValue(embedding))
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "Original question",
      answer = "Some answer",
      contexts = Seq("Context")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
  }

  it should "include details about generated questions in result" in {
    val questionsResponse = """["Generated Q1", "Generated Q2", "Generated Q3"]"""

    val embedding = Seq(1.0, 0.0, 0.0)

    val mockLLM       = new MockLLMClients.SimpleMock(questionsResponse)
    val mockEmbedding = createMockEmbeddingClient(Map.empty.withDefaultValue(embedding))
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "Original question",
      answer = "Some answer",
      contexts = Seq("Context")
    )

    val result = metric.evaluate(sample)

    result.isRight shouldBe true
    val details = result.toOption.get.details
    details.get("numQuestions") shouldBe Some(3)
    details.get("generatedQuestions").map(_.asInstanceOf[Seq[String]].size) shouldBe Some(3)
  }

  it should "reject invalid number of generated questions" in {
    val mockLLM       = new MockLLMClients.SimpleMock("[]")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)

    an[IllegalArgumentException] should be thrownBy {
      AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig, numGeneratedQuestions = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig, numGeneratedQuestions = -1)
    }
  }

  it should "use default number of questions" in {
    AnswerRelevancy.DEFAULT_NUM_QUESTIONS shouldBe 3
  }

  it should "propagate LLM client errors" in {
    val mockLLM       = new MockLLMClients.FailingMock("LLM service unavailable")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

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
    val mockLLM       = new MockLLMClients.SimpleMock("not valid json at all")
    val mockEmbedding = createMockEmbeddingClient(Map.empty)
    val metric        = AnswerRelevancy(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital?",
      answer = "Paris is the capital.",
      contexts = Seq("Some context")
    )

    val result = metric.evaluate(sample)

    result.isLeft shouldBe true
  }
}
