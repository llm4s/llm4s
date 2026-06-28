package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Edge case tests for RAGASEvaluator.
 */
class RAGASEvaluatorEdgeCasesSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  class MockLLMClient(responses: Seq[String] = Seq("[]")) extends LLMClient {
    private var responseIndex = 0
    var callCount             = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount += 1
      val response = responses(responseIndex % responses.size)
      responseIndex += 1
      Right(
        Completion(
          id = "test",
          created = 0L,
          content = response,
          model = "test",
          message = AssistantMessage(response)
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  class FailingLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(EvaluationError("LLM call failed"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(1.0, 0.0, 0.0))))
  }

  private val embeddingConfig = EmbeddingModelConfig("test-model", 3)

  private def createEmbeddingClient() = new EmbeddingClient(new MockEmbeddingProvider())

  // ==========================================================================
  // evaluateMetric tests
  // ==========================================================================

  "RAGASEvaluator.evaluateMetric" should "evaluate a single metric by name" in {
    val responses = Seq(
      """["claim1"]""",
      """[{"claim": "claim1", "supported": true}]"""
    )
    val evaluator = RAGASEvaluator(new MockLLMClient(responses), createEmbeddingClient(), embeddingConfig)

    val sample = EvalSample("Q?", "A.", Seq("context"))
    val result = evaluator.evaluateMetric(sample, "faithfulness")
    result.isRight shouldBe true
    result.toOption.get.metricName shouldBe "faithfulness"
    result.toOption.get.score should ((be >= 0.0).and(be <= 1.0))
  }

  it should "return error for unknown metric" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    val sample    = EvalSample("Q?", "A.", Seq("context"))
    val result    = evaluator.evaluateMetric(sample, "nonexistent_metric")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("Unknown metric")
  }

  it should "return error when metric cannot evaluate the sample" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    // context_recall requires ground truth
    val sample = EvalSample("Q?", "A.", Seq("context"), groundTruth = None)
    val result = evaluator.evaluateMetric(sample, "context_recall")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("missing required inputs")
  }

  // ==========================================================================
  // withMetrics tests
  // ==========================================================================

  "RAGASEvaluator.withMetrics" should "create evaluator with subset of metrics" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    evaluator.getActiveMetrics should have size 4

    val filtered = evaluator.withMetrics(Set("faithfulness", "answer_relevancy"))
    filtered.getActiveMetrics should have size 2
    filtered.getActiveMetrics.map(_.name).toSet shouldBe Set("faithfulness", "answer_relevancy")
  }

  it should "fall back to defaults when filtering results in empty metrics" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    // When filtering to unknown names, the constructor falls back to defaults since empty list
    val filtered = evaluator.withMetrics(Set("unknown"))
    // The RAGASEvaluator uses defaults when metrics seq is empty
    filtered.getActiveMetrics should have size 4
  }

  // ==========================================================================
  // withOptions tests
  // ==========================================================================

  "RAGASEvaluator.withOptions" should "create evaluator with new options" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    val newOpts   = EvaluatorOptions(parallelEvaluation = true, maxConcurrency = 8)
    val updated   = evaluator.withOptions(newOpts)
    // Should preserve metrics
    updated.getActiveMetrics should have size 4
  }

  // ==========================================================================
  // evaluate with no applicable metrics
  // ==========================================================================

  "RAGASEvaluator.evaluate" should "return error when no metrics are applicable" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    // Filter to only ground-truth metrics, but don't provide ground truth
    val filtered = evaluator.withMetrics(Set("context_precision", "context_recall"))

    val sample = EvalSample("Q?", "A.", Seq("context"), groundTruth = None)
    val result = filtered.evaluate(sample)
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("No applicable metrics")
  }

  it should "succeed with partial metric failures" in {
    // Faithfulness will succeed, answer_relevancy might too
    val responses = Seq(
      """["claim1"]""",
      """[{"claim": "claim1", "supported": true}]""",
      """["question1"]"""
    )
    val evaluator =
      RAGASEvaluator(new MockLLMClient(responses), createEmbeddingClient(), embeddingConfig)
        .withMetrics(Set("faithfulness"))

    val sample = EvalSample("Q?", "The answer is A.", Seq("context about A"))
    val result = evaluator.evaluate(sample)
    result.isRight shouldBe true
    val evalResult = result.toOption.get
    evalResult.ragasScore should ((be >= 0.0).and(be <= 1.0))
  }

  it should "return first error when all metrics fail" in {
    val evaluator =
      RAGASEvaluator(new FailingLLMClient(), createEmbeddingClient(), embeddingConfig)
        .withMetrics(Set("faithfulness"))

    val sample = EvalSample("Q?", "A.", Seq("context"))
    val result = evaluator.evaluate(sample)
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // evaluateBatch tests
  // ==========================================================================

  "RAGASEvaluator.evaluateBatch" should "return empty summary for empty batch" in {
    val evaluator = RAGASEvaluator(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    val result    = evaluator.evaluateBatch(Seq.empty)
    result.isRight shouldBe true
    val summary = result.toOption.get
    summary.sampleCount shouldBe 0
    summary.results shouldBe empty
    summary.averages shouldBe empty
    summary.overallRagasScore shouldBe 0.0
  }

  it should "compute averages across samples" in {
    val responses = Seq(
      // Sample 1: faithfulness
      """["claim1"]""",
      """[{"claim": "claim1", "supported": true}]""",
      // Sample 2: faithfulness
      """["claim2"]""",
      """[{"claim": "claim2", "supported": false}]"""
    )
    val evaluator =
      RAGASEvaluator(new MockLLMClient(responses), createEmbeddingClient(), embeddingConfig)
        .withMetrics(Set("faithfulness"))

    val samples = Seq(
      EvalSample("Q1?", "A1.", Seq("ctx1")),
      EvalSample("Q2?", "A2.", Seq("ctx2"))
    )

    val result = evaluator.evaluateBatch(samples)
    result.isRight shouldBe true
    val summary = result.toOption.get
    summary.sampleCount shouldBe 2
    (summary.averages should contain).key("faithfulness")
    summary.overallRagasScore should ((be >= 0.0).and(be <= 1.0))
  }

  // ==========================================================================
  // evaluateDataset tests
  // ==========================================================================

  "RAGASEvaluator.evaluateDataset" should "evaluate all samples in dataset" in {
    val responses = Seq(
      """["claim"]""",
      """[{"claim": "claim", "supported": true}]"""
    )
    val evaluator =
      RAGASEvaluator(new MockLLMClient(responses), createEmbeddingClient(), embeddingConfig)
        .withMetrics(Set("faithfulness"))

    val dataset = TestDataset.single("Q?", "A.", Seq("ctx"))
    val result  = evaluator.evaluateDataset(dataset)
    result.isRight shouldBe true
    result.toOption.get.sampleCount shouldBe 1
  }

  // ==========================================================================
  // basic evaluator
  // ==========================================================================

  "RAGASEvaluator.basic" should "only include metrics that don't require ground truth" in {
    val evaluator = RAGASEvaluator.basic(new MockLLMClient(), createEmbeddingClient(), embeddingConfig)
    val names     = evaluator.getActiveMetrics.map(_.name).toSet
    names shouldBe Set("faithfulness", "answer_relevancy")
  }

  // ==========================================================================
  // Metric constants
  // ==========================================================================

  "RAGASEvaluator constants" should "have correct values" in {
    RAGASEvaluator.FAITHFULNESS shouldBe "faithfulness"
    RAGASEvaluator.ANSWER_RELEVANCY shouldBe "answer_relevancy"
    RAGASEvaluator.CONTEXT_PRECISION shouldBe "context_precision"
    RAGASEvaluator.CONTEXT_RECALL shouldBe "context_recall"
  }
}
