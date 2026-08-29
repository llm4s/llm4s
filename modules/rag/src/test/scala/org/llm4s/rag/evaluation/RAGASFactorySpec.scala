package org.llm4s.rag.evaluation

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGASFactorySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  class MockLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "test",
          created = 0L,
          content = "[]",
          model = "test",
          message = AssistantMessage("[]")
        )
      )

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

  private val mockLLM             = new MockLLMClient
  private val mockEmbeddingClient = new EmbeddingClient(new MockEmbeddingProvider())
  private val embeddingConfig     = EmbeddingModelConfig("test-model", 3)

  "RAGASFactory.create" should "create evaluator with all four default metrics" in {
    val evaluator = RAGASFactory.create(mockLLM, mockEmbeddingClient, embeddingConfig)
    val metrics   = evaluator.getActiveMetrics
    metrics should have size 4
    metrics.map(_.name).toSet shouldBe Set("faithfulness", "answer_relevancy", "context_precision", "context_recall")
  }

  "RAGASFactory.withMetrics" should "create evaluator with only specified metrics" in {
    val evaluator = RAGASFactory.withMetrics(mockLLM, mockEmbeddingClient, embeddingConfig, Set("faithfulness"))
    val metrics   = evaluator.getActiveMetrics
    metrics should have size 1
    metrics.head.name shouldBe "faithfulness"
  }

  it should "create evaluator with multiple specified metrics" in {
    val evaluator =
      RAGASFactory.withMetrics(mockLLM, mockEmbeddingClient, embeddingConfig, Set("faithfulness", "context_recall"))
    val metrics = evaluator.getActiveMetrics
    metrics should have size 2
    metrics.map(_.name).toSet shouldBe Set("faithfulness", "context_recall")
  }

  it should "fall back to defaults when unknown names given" in {
    val evaluator = RAGASFactory.withMetrics(mockLLM, mockEmbeddingClient, embeddingConfig, Set("nonexistent_metric"))
    // When no metrics match, the evaluator falls back to all 4 defaults since empty list triggers default
    evaluator.getActiveMetrics should have size 4
  }

  it should "ignore unknown metric names alongside valid ones" in {
    val evaluator =
      RAGASFactory.withMetrics(mockLLM, mockEmbeddingClient, embeddingConfig, Set("faithfulness", "nonexistent"))
    val metrics = evaluator.getActiveMetrics
    metrics should have size 1
    metrics.head.name shouldBe "faithfulness"
  }

  "RAGASFactory.basic" should "create evaluator with only faithfulness and answer_relevancy" in {
    val evaluator = RAGASFactory.basic(mockLLM, mockEmbeddingClient, embeddingConfig)
    val metrics   = evaluator.getActiveMetrics
    metrics should have size 2
    metrics.map(_.name).toSet shouldBe Set("faithfulness", "answer_relevancy")
  }

  "RAGASFactory.faithfulness" should "create a Faithfulness metric with default batch size" in {
    val metric = RAGASFactory.faithfulness(mockLLM)
    metric.name shouldBe "faithfulness"
  }

  it should "create a Faithfulness metric with custom batch size" in {
    val metric = RAGASFactory.faithfulness(mockLLM, batchSize = 10)
    metric.name shouldBe "faithfulness"
  }

  "RAGASFactory.answerRelevancy" should "create an AnswerRelevancy metric" in {
    val metric = RAGASFactory.answerRelevancy(mockLLM, mockEmbeddingClient, embeddingConfig)
    metric.name shouldBe "answer_relevancy"
  }

  it should "create with custom number of generated questions" in {
    val metric = RAGASFactory.answerRelevancy(mockLLM, mockEmbeddingClient, embeddingConfig, numGeneratedQuestions = 5)
    metric.name shouldBe "answer_relevancy"
  }

  "RAGASFactory.contextPrecision" should "create a ContextPrecision metric" in {
    val metric = RAGASFactory.contextPrecision(mockLLM)
    metric.name shouldBe "context_precision"
  }

  "RAGASFactory.contextRecall" should "create a ContextRecall metric" in {
    val metric = RAGASFactory.contextRecall(mockLLM)
    metric.name shouldBe "context_recall"
  }

  "RAGASFactory.availableMetrics" should "contain all four metric names" in {
    RAGASFactory.availableMetrics shouldBe Set(
      "faithfulness",
      "answer_relevancy",
      "context_precision",
      "context_recall"
    )
  }

  "RAGASFactory.metricsRequiringGroundTruth" should "contain context_precision and context_recall" in {
    RAGASFactory.metricsRequiringGroundTruth shouldBe Set("context_precision", "context_recall")
  }

  "RAGASFactory.metricsWithoutGroundTruth" should "contain faithfulness and answer_relevancy" in {
    RAGASFactory.metricsWithoutGroundTruth shouldBe Set("faithfulness", "answer_relevancy")
  }

  it should "be disjoint from metricsRequiringGroundTruth" in {
    (RAGASFactory.metricsWithoutGroundTruth & RAGASFactory.metricsRequiringGroundTruth) shouldBe empty
  }

  it should "together with metricsRequiringGroundTruth cover all available metrics" in {
    (RAGASFactory.metricsWithoutGroundTruth ++ RAGASFactory.metricsRequiringGroundTruth) shouldBe RAGASFactory.availableMetrics
  }
}
