package org.llm4s.rag

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.knowledgegraph.graphrag.GraphRAGConfig
import org.llm4s.knowledgegraph.storage.InMemoryGraphStore
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.vectorstore.FusionStrategy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGSpec extends AnyFlatSpec with Matchers {

  // ========== RAGTypes Tests ==========

  "EmbeddingProvider" should "parse from string correctly" in {
    EmbeddingProvider.fromString("openai") shouldBe Some(EmbeddingProvider.OpenAI)
    EmbeddingProvider.fromString("OpenAI") shouldBe Some(EmbeddingProvider.OpenAI)
    EmbeddingProvider.fromString("OPENAI") shouldBe Some(EmbeddingProvider.OpenAI)
    EmbeddingProvider.fromString("voyage") shouldBe Some(EmbeddingProvider.Voyage)
    EmbeddingProvider.fromString("ollama") shouldBe Some(EmbeddingProvider.Ollama)
    EmbeddingProvider.fromString("unknown") shouldBe None
  }

  it should "have correct names" in {
    EmbeddingProvider.OpenAI.name shouldBe "openai"
    EmbeddingProvider.Voyage.name shouldBe "voyage"
    EmbeddingProvider.Ollama.name shouldBe "ollama"
  }

  it should "list all values" in {
    (EmbeddingProvider.values should contain).allOf(
      EmbeddingProvider.OpenAI,
      EmbeddingProvider.Voyage,
      EmbeddingProvider.Ollama
    )
  }

  "RerankingStrategy" should "have correct case objects" in {
    RerankingStrategy.None shouldBe a[RerankingStrategy]
    RerankingStrategy.LLM shouldBe a[RerankingStrategy]
    RerankingStrategy.Cohere() shouldBe a[RerankingStrategy]
  }

  it should "have default Cohere model" in {
    RerankingStrategy.Cohere().model shouldBe "rerank-english-v3.0"
    RerankingStrategy.Cohere("custom-model").model shouldBe "custom-model"
  }

  "RAGSearchResult" should "create with required fields" in {
    val result = RAGSearchResult(
      id = "doc1-chunk-0",
      content = "Some content",
      score = 0.85
    )
    result.id shouldBe "doc1-chunk-0"
    result.content shouldBe "Some content"
    result.score shouldBe 0.85
    result.metadata shouldBe empty
    result.vectorScore shouldBe None
    result.keywordScore shouldBe None
  }

  it should "create with optional fields" in {
    val result = RAGSearchResult(
      id = "doc1-chunk-0",
      content = "Some content",
      score = 0.85,
      metadata = Map("source" -> "test.pdf"),
      vectorScore = Some(0.9),
      keywordScore = Some(0.7)
    )
    result.metadata shouldBe Map("source" -> "test.pdf")
    result.vectorScore shouldBe Some(0.9)
    result.keywordScore shouldBe Some(0.7)
  }

  "RAGAnswerResult" should "create with all fields" in {
    val contexts = Seq(RAGSearchResult("id", "content", 0.8))
    val usage    = Some(TokenUsage(100, 50, 150))

    val result = RAGAnswerResult(
      answer = "The answer is 42",
      question = "What is the answer?",
      contexts = contexts,
      usage = usage
    )

    result.answer shouldBe "The answer is 42"
    result.question shouldBe "What is the answer?"
    result.contexts shouldBe contexts
    result.usage shouldBe usage
  }

  "RAGStats" should "create with counts" in {
    val stats = RAGStats(
      documentCount = 10,
      chunkCount = 100,
      vectorCount = 100L
    )
    stats.documentCount shouldBe 10
    stats.chunkCount shouldBe 100
    stats.vectorCount shouldBe 100L
  }

  // ========== RAGConfig Tests ==========

  "RAGConfig" should "have sensible defaults" in {
    val config = RAGConfig()
    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe None
    config.embeddingDimensions shouldBe None
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.fusionStrategy shouldBe a[FusionStrategy.RRF]
    config.topK shouldBe 5
    config.rerankingStrategy shouldBe RerankingStrategy.None
    config.rerankTopK shouldBe 30
    config.vectorStorePath shouldBe None
    config.llmClient shouldBe None
    config.tracer shouldBe None
  }

  it should "support fluent embedding configuration" in {
    val config = RAGConfig()
      .withEmbeddings(EmbeddingProvider.Voyage)
      .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
      .withEmbeddingDimensions(3072)

    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe Some("text-embedding-3-large")
    config.embeddingDimensions shouldBe Some(3072)
  }

  it should "support fluent chunking configuration" in {
    val config = RAGConfig()
      .withChunking(ChunkerFactory.Strategy.Markdown)

    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Markdown

    val config2 = RAGConfig()
      .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)

    config2.chunkingConfig.targetSize shouldBe 800
    config2.chunkingConfig.overlap shouldBe 150
  }

  it should "support fluent fusion configuration" in {
    val config1 = RAGConfig().withRRF(80)
    config1.fusionStrategy shouldBe FusionStrategy.RRF(80)

    val config2 = RAGConfig().withWeightedScore(0.7, 0.3)
    config2.fusionStrategy shouldBe FusionStrategy.WeightedScore(0.7, 0.3)

    val config3 = RAGConfig().vectorOnly
    config3.fusionStrategy shouldBe FusionStrategy.VectorOnly

    val config4 = RAGConfig().keywordOnly
    config4.fusionStrategy shouldBe FusionStrategy.KeywordOnly
  }

  it should "support fluent reranking configuration" in {
    val config1 = RAGConfig().withCohereReranking()
    config1.rerankingStrategy shouldBe RerankingStrategy.Cohere("rerank-english-v3.0")

    val config2 = RAGConfig().withCohereReranking("custom-model")
    config2.rerankingStrategy shouldBe RerankingStrategy.Cohere("custom-model")

    val config3 = RAGConfig().withLLMReranking
    config3.rerankingStrategy shouldBe RerankingStrategy.LLM

    val config4 = RAGConfig().withRerankTopK(50)
    config4.rerankTopK shouldBe 50
  }

  it should "support fluent storage configuration" in {
    val config1 = RAGConfig().withSQLite("./test.db")
    config1.vectorStorePath shouldBe Some("./test.db")
    config1.keywordIndexPath shouldBe Some("./test-fts.db")

    val config2 = RAGConfig().inMemory
    config2.vectorStorePath shouldBe None
    config2.keywordIndexPath shouldBe None
  }

  it should "support fluent answer generation configuration" in {
    val config = RAGConfig()
      .withSystemPrompt("Custom prompt")
      .withTopK(10)

    config.systemPrompt shouldBe Some("Custom prompt")
    config.topK shouldBe 10
  }

  it should "support GraphRAG configuration" in {
    val store  = new InMemoryGraphStore()
    val config = RAGConfig().withGraphStore(store).withGraphRAG(GraphRAGConfig(localTraversalDepth = 3))

    config.graphStore shouldBe Some(store)
    config.graphRAGConfig.localTraversalDepth shouldBe 3
    config.hasGraphRAG shouldBe true
  }

  it should "report GraphRAG disabled by default" in {
    RAGConfig().hasGraphRAG shouldBe false
  }

  "RAGConfig.default" should "equal empty RAGConfig" in {
    RAGConfig.default shouldBe RAGConfig()
  }

  "RAGConfig.production" should "configure persistent storage" in {
    val config = RAGConfig.production("/var/data/rag.db")
    config.vectorStorePath shouldBe Some("/var/data/rag.db")
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.chunkingConfig.targetSize shouldBe 800
    config.chunkingConfig.overlap shouldBe 150
  }

  "RAGConfig.development" should "configure for testing" in {
    val config = RAGConfig.development
    config.vectorStorePath shouldBe None
    config.topK shouldBe 3
  }

  // ========== RAG Builder Tests ==========

  "RAG.builder" should "return default config" in {
    val config = RAG.builder()
    config shouldBe RAGConfig.default
  }

  "RAGConfigOps.build" should "be accessible via implicit" in {
    // This tests that the implicit conversion is available at compile time
    // The actual build would require env vars, so we just verify the config
    val config = RAGConfig()
    config.withEmbeddings(EmbeddingProvider.OpenAI) shouldBe a[RAGConfig]

    // Verify the implicit class exists (would fail to compile if not)
    val ops = new RAG.RAGConfigOps(config)
    ops shouldBe a[RAG.RAGConfigOps]
  }
}
