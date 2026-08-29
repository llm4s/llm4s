package org.llm4s.rag

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.TokenUsage

/**
 * Tests for RAG type definitions.
 */
class RAGTypesSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // EmbeddingProvider Tests
  // ==========================================================================

  "EmbeddingProvider" should "have correct names" in {
    EmbeddingProvider.OpenAI.name shouldBe "openai"
    EmbeddingProvider.Voyage.name shouldBe "voyage"
    EmbeddingProvider.Ollama.name shouldBe "ollama"
  }

  it should "parse from string correctly" in {
    EmbeddingProvider.fromString("openai") shouldBe Some(EmbeddingProvider.OpenAI)
    EmbeddingProvider.fromString("voyage") shouldBe Some(EmbeddingProvider.Voyage)
    EmbeddingProvider.fromString("ollama") shouldBe Some(EmbeddingProvider.Ollama)
    EmbeddingProvider.fromString("OPENAI") shouldBe Some(EmbeddingProvider.OpenAI)
  }

  it should "return None for unknown provider" in {
    EmbeddingProvider.fromString("unknown") shouldBe None
    EmbeddingProvider.fromString("") shouldBe None
  }

  it should "have all values in values sequence" in {
    (EmbeddingProvider.values should contain).allOf(
      EmbeddingProvider.OpenAI,
      EmbeddingProvider.Voyage,
      EmbeddingProvider.Ollama
    )
    EmbeddingProvider.values should have size 3
  }

  // ==========================================================================
  // RerankingStrategy Tests
  // ==========================================================================

  "RerankingStrategy.None" should "be a singleton" in {
    RerankingStrategy.None shouldBe RerankingStrategy.None
  }

  "RerankingStrategy.Cohere" should "have default model" in {
    val strategy = RerankingStrategy.Cohere()
    strategy.model shouldBe "rerank-english-v3.0"
  }

  it should "accept custom model" in {
    val strategy = RerankingStrategy.Cohere("rerank-multilingual-v3.0")
    strategy.model shouldBe "rerank-multilingual-v3.0"
  }

  "RerankingStrategy.LLM" should "be a singleton" in {
    RerankingStrategy.LLM shouldBe RerankingStrategy.LLM
  }

  // ==========================================================================
  // RAGSearchResult Tests
  // ==========================================================================

  "RAGSearchResult" should "store all fields" in {
    val result = RAGSearchResult(
      id = "chunk-1",
      content = "This is test content",
      score = 0.95,
      metadata = Map("source" -> "test.txt", "page" -> "1"),
      vectorScore = Some(0.92),
      keywordScore = Some(0.88)
    )

    result.id shouldBe "chunk-1"
    result.content shouldBe "This is test content"
    result.score shouldBe 0.95
    result.metadata shouldBe Map("source" -> "test.txt", "page" -> "1")
    result.vectorScore shouldBe Some(0.92)
    result.keywordScore shouldBe Some(0.88)
  }

  it should "have default empty metadata" in {
    val result = RAGSearchResult("id", "content", 0.5)
    result.metadata shouldBe Map.empty
  }

  it should "have default None for scores" in {
    val result = RAGSearchResult("id", "content", 0.5)
    result.vectorScore shouldBe None
    result.keywordScore shouldBe None
  }

  it should "support equality" in {
    val r1 = RAGSearchResult("id", "content", 0.5)
    val r2 = RAGSearchResult("id", "content", 0.5)
    val r3 = RAGSearchResult("id2", "content", 0.5)

    r1 shouldBe r2
    r1 should not be r3
  }

  // ==========================================================================
  // RAGAnswerResult Tests
  // ==========================================================================

  "RAGAnswerResult" should "store all fields" in {
    val contexts = Seq(
      RAGSearchResult("c1", "Context 1", 0.9),
      RAGSearchResult("c2", "Context 2", 0.8)
    )
    val usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))

    val result = RAGAnswerResult(
      answer = "The answer is 42",
      question = "What is the meaning of life?",
      contexts = contexts,
      usage = usage
    )

    result.answer shouldBe "The answer is 42"
    result.question shouldBe "What is the meaning of life?"
    result.contexts should have size 2
    result.usage shouldBe usage
  }

  it should "have default None for usage" in {
    val result = RAGAnswerResult("answer", "question", Seq.empty)
    result.usage shouldBe None
  }

  // ==========================================================================
  // RAGStats Tests
  // ==========================================================================

  "RAGStats" should "store statistics" in {
    val stats = RAGStats(
      documentCount = 10,
      chunkCount = 150,
      vectorCount = 150L
    )

    stats.documentCount shouldBe 10
    stats.chunkCount shouldBe 150
    stats.vectorCount shouldBe 150L
  }

  it should "support equality" in {
    val s1 = RAGStats(10, 100, 100L)
    val s2 = RAGStats(10, 100, 100L)
    val s3 = RAGStats(20, 100, 100L)

    s1 shouldBe s2
    s1 should not be s3
  }
}
