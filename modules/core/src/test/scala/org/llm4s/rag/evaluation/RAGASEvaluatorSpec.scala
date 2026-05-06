package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGASEvaluatorSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def mockCompletion(content: String): Completion = Completion(
    id = "test-id",
    created = System.currentTimeMillis(),
    content = content,
    model = "test-model",
    message = AssistantMessage(content)
  )

  // Mock LLM client that returns sequential responses
  class MockLLMClient(responses: Seq[String] = Seq("[]")) extends LLMClient {
    private var responseIndex = 0
    var callCount             = 0

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      callCount += 1
      val response = responses(responseIndex % responses.size)
      responseIndex += 1
      Right(mockCompletion(response))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = request.input.map(_ => Seq(1.0, 0.0, 0.0))
      Right(EmbeddingResponse(embeddings = embeddings))
    }
  }

  def createMockEmbeddingClient(): EmbeddingClient =
    new EmbeddingClient(new MockEmbeddingProvider())

  private val testEmbeddingConfig = EmbeddingModelConfig("test-model", 3)

  "RAGASEvaluator" should "evaluate a sample with all metrics" in {
    // Sequential responses for each LLM call:
    // 1. Faithfulness: extract claims
    // 2. Faithfulness: verify claims
    // 3. AnswerRelevancy: generate questions
    // 4. ContextPrecision: assess relevance
    // 5. ContextRecall: extract facts
    // 6. ContextRecall: attribute facts
    val responses = Seq(
      """["Paris is the capital"]""", // Faithfulness claim extraction
      """[{"claim": "Paris is the capital", "supported": true, "evidence": "Context says so"}]""", // Faithfulness verification
      """["What is the capital?", "Which city is the capital?", "What is France's capital?"]""", // AnswerRelevancy
      """[{"index": 0, "relevant": true}]""",                                                    // ContextPrecision
      """["Paris is the capital"]""",                                        // ContextRecall fact extraction
      """[{"fact": "Paris is the capital", "covered": true, "source": 1}]""" // ContextRecall attribution
    )

    val mockLLM       = new MockLLMClient(responses)
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital of France.",
      contexts = Seq("Paris is the capital and largest city of France."),
      groundTruth = Some("The capital of France is Paris.")
    )

    val result = evaluator.evaluate(sample)

    result.isRight shouldBe true
    val evalResult = result.toOption.get

    evalResult.metrics.size shouldBe 4
    evalResult.ragasScore should be >= 0.0
    evalResult.ragasScore should be <= 1.0

    // Check all metrics are present
    evalResult.getMetric("faithfulness") shouldBe defined
    evalResult.getMetric("answer_relevancy") shouldBe defined
    evalResult.getMetric("context_precision") shouldBe defined
    evalResult.getMetric("context_recall") shouldBe defined
  }

  it should "evaluate sample without ground truth (only basic metrics)" in {
    // Without ground truth, only Faithfulness and AnswerRelevancy are evaluated
    val responses = Seq(
      """["Paris is the capital"]""", // Faithfulness claim extraction
      """[{"claim": "Paris is the capital", "supported": true, "evidence": "Context"}]""", // Faithfulness verification
      """["Question 1", "Question 2", "Question 3"]"""                                     // AnswerRelevancy
    )

    val mockLLM       = new MockLLMClient(responses)
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample(
      question = "What is the capital of France?",
      answer = "Paris is the capital.",
      contexts = Seq("Paris is the capital of France."),
      groundTruth = None // No ground truth
    )

    val result = evaluator.evaluate(sample)

    result.isRight shouldBe true
    val evalResult = result.toOption.get

    // Only 2 metrics should be evaluated (faithfulness and answer_relevancy)
    evalResult.metrics.size shouldBe 2
    evalResult.getMetric("faithfulness") shouldBe defined
    evalResult.getMetric("answer_relevancy") shouldBe defined
    evalResult.getMetric("context_precision") shouldBe None
    evalResult.getMetric("context_recall") shouldBe None
  }

  it should "evaluate a batch of samples" in {
    // Each sample needs 6 LLM calls, use a repeating sequence
    val sampleResponses = Seq(
      """["Claim"]""",                                       // Faithfulness claim extraction
      """[{"claim": "Claim", "supported": true}]""",         // Faithfulness verification
      """["Q1", "Q2", "Q3"]""",                              // AnswerRelevancy
      """[{"index": 0, "relevant": true}]""",                // ContextPrecision
      """["Fact"]""",                                        // ContextRecall fact extraction
      """[{"fact": "Fact", "covered": true, "source": 1}]""" // ContextRecall attribution
    )

    // Repeat for 3 samples
    val responses = sampleResponses ++ sampleResponses ++ sampleResponses

    val mockLLM       = new MockLLMClient(responses)
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val samples = Seq(
      EvalSample("Q1", "A1", Seq("C1"), Some("GT1")),
      EvalSample("Q2", "A2", Seq("C2"), Some("GT2")),
      EvalSample("Q3", "A3", Seq("C3"), Some("GT3"))
    )

    val result = evaluator.evaluateBatch(samples)

    result.isRight shouldBe true
    val summary = result.toOption.get

    summary.sampleCount shouldBe 3
    (summary.averages.keys should contain).allOf("faithfulness", "answer_relevancy")
    summary.overallRagasScore should be >= 0.0
    summary.overallRagasScore should be <= 1.0
  }

  it should "return empty summary for empty batch" in {
    val mockLLM       = new MockLLMClient()
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val result = evaluator.evaluateBatch(Seq.empty)

    result.isRight shouldBe true
    val summary = result.toOption.get

    summary.sampleCount shouldBe 0
    summary.results shouldBe empty
    summary.averages shouldBe empty
  }

  it should "create evaluator with only specific metrics" in {
    val mockLLM       = new MockLLMClient()
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val filteredEvaluator = evaluator.withMetrics(Set("faithfulness", "answer_relevancy"))

    filteredEvaluator.getActiveMetrics.size shouldBe 2
    (filteredEvaluator.getActiveMetrics.map(_.name) should contain).allOf("faithfulness", "answer_relevancy")
  }

  it should "evaluate a single metric" in {
    val responses = Seq(
      """["Claim 1"]""",                                                   // Faithfulness claim extraction
      """[{"claim": "Claim 1", "supported": true, "evidence": "Found"}]""" // Faithfulness verification
    )

    val mockLLM       = new MockLLMClient(responses)
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample("Question", "Answer", Seq("Context"))

    val result = evaluator.evaluateMetric(sample, "faithfulness")

    result.isRight shouldBe true
    result.toOption.get.metricName shouldBe "faithfulness"
  }

  it should "fail for unknown metric" in {
    val mockLLM       = new MockLLMClient()
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val sample = EvalSample("Question", "Answer", Seq("Context"))

    val result = evaluator.evaluateMetric(sample, "unknown_metric")

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Unknown metric")
  }

  it should "provide basic evaluator without ground truth metrics" in {
    val mockLLM       = new MockLLMClient()
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator.basic(mockLLM, mockEmbedding, testEmbeddingConfig)

    evaluator.getActiveMetrics.size shouldBe 2
    (evaluator.getActiveMetrics.map(_.name) should contain).allOf("faithfulness", "answer_relevancy")
    evaluator.getActiveMetrics.map(_.name) should not contain "context_precision"
    evaluator.getActiveMetrics.map(_.name) should not contain "context_recall"
  }

  it should "have correct metric constants" in {
    RAGASEvaluator.FAITHFULNESS shouldBe "faithfulness"
    RAGASEvaluator.ANSWER_RELEVANCY shouldBe "answer_relevancy"
    RAGASEvaluator.CONTEXT_PRECISION shouldBe "context_precision"
    RAGASEvaluator.CONTEXT_RECALL shouldBe "context_recall"
  }

  it should "evaluate dataset" in {
    // Each sample without ground truth needs 3 LLM calls (faithfulness: 2, answer_relevancy: 1)
    val sampleResponses = Seq(
      """["Claim"]""",                               // Faithfulness claim extraction
      """[{"claim": "Claim", "supported": true}]""", // Faithfulness verification
      """["Q1", "Q2", "Q3"]"""                       // AnswerRelevancy
    )

    // Repeat for 2 samples
    val responses = sampleResponses ++ sampleResponses

    val mockLLM       = new MockLLMClient(responses)
    val mockEmbedding = createMockEmbeddingClient()
    val evaluator     = RAGASEvaluator(mockLLM, mockEmbedding, testEmbeddingConfig)

    val dataset = TestDataset(
      name = "test",
      samples = Seq(
        EvalSample("Q1", "A1", Seq("C1")),
        EvalSample("Q2", "A2", Seq("C2"))
      )
    )

    val result = evaluator.evaluateDataset(dataset)

    result.isRight shouldBe true
    result.toOption.get.sampleCount shouldBe 2
  }
}
