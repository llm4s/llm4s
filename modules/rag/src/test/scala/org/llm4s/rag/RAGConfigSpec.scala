package org.llm4s.rag

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.vectorstore.FusionStrategy
import org.llm4s.rag.loader.{ DirectoryLoader, LoadingConfig }

/**
 * Tests for RAGConfig builder pattern.
 */
class RAGConfigSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Default Configuration Tests
  // ==========================================================================

  "RAGConfig" should "have sensible defaults" in {
    val config = RAGConfig()

    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe None
    config.embeddingDimensions shouldBe None
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.topK shouldBe 5
    config.rerankingStrategy shouldBe RerankingStrategy.None
    config.rerankTopK shouldBe 30
    config.vectorStorePath shouldBe None
    config.keywordIndexPath shouldBe None
    config.llmClient shouldBe None
    config.systemPrompt shouldBe None
    config.tracer shouldBe None
    config.documentLoaders shouldBe empty
  }

  "RAGConfig.default" should "return default configuration" in {
    val config = RAGConfig.default
    config shouldBe RAGConfig()
  }

  "RAGConfig.development" should "have in-memory storage and smaller topK" in {
    val config = RAGConfig.development
    config.vectorStorePath shouldBe None
    config.topK shouldBe 3
  }

  "RAGConfig.production" should "use SQLite storage" in {
    val config = RAGConfig.production("./data/rag.db")
    config.vectorStorePath shouldBe Some("./data/rag.db")
    config.keywordIndexPath shouldBe Some("./data/rag-fts.db")
  }

  // ==========================================================================
  // Embedding Configuration Tests
  // ==========================================================================

  "RAGConfig.withEmbeddings" should "set provider" in {
    val config = RAGConfig().withEmbeddings(EmbeddingProvider.Voyage)
    config.embeddingProvider shouldBe EmbeddingProvider.Voyage
  }

  it should "set provider and model" in {
    val config = RAGConfig().withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe Some("text-embedding-3-large")
  }

  it should "set provider, model, and dimensions" in {
    val config = RAGConfig().withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large", 3072)
    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe Some("text-embedding-3-large")
    config.embeddingDimensions shouldBe Some(3072)
  }

  "RAGConfig.withEmbeddingDimensions" should "override dimensions" in {
    val config = RAGConfig().withEmbeddingDimensions(1024)
    config.embeddingDimensions shouldBe Some(1024)
  }

  // ==========================================================================
  // Chunking Configuration Tests
  // ==========================================================================

  "RAGConfig.withChunking" should "set chunking strategy" in {
    val config = RAGConfig().withChunking(ChunkerFactory.Strategy.Markdown)
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Markdown
  }

  it should "set strategy with custom config" in {
    val chunkConfig = ChunkingConfig(targetSize = 500, maxSize = 750, overlap = 100)
    val config      = RAGConfig().withChunking(ChunkerFactory.Strategy.Sentence, chunkConfig)
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.chunkingConfig shouldBe chunkConfig
  }

  it should "set strategy with size and overlap" in {
    val config = RAGConfig().withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.chunkingConfig.targetSize shouldBe 800
    config.chunkingConfig.overlap shouldBe 150
    config.chunkingConfig.maxSize shouldBe 1200 // 800 * 1.5
  }

  // ==========================================================================
  // Search Configuration Tests
  // ==========================================================================

  "RAGConfig.withFusion" should "set fusion strategy" in {
    val config = RAGConfig().withFusion(FusionStrategy.VectorOnly)
    config.fusionStrategy shouldBe FusionStrategy.VectorOnly
  }

  "RAGConfig.withRRF" should "set RRF fusion with default k" in {
    val config = RAGConfig().withRRF()
    config.fusionStrategy shouldBe a[FusionStrategy.RRF]
    config.fusionStrategy.asInstanceOf[FusionStrategy.RRF].k shouldBe 60
  }

  it should "set RRF fusion with custom k" in {
    val config = RAGConfig().withRRF(100)
    config.fusionStrategy.asInstanceOf[FusionStrategy.RRF].k shouldBe 100
  }

  "RAGConfig.withWeightedScore" should "set weighted score fusion" in {
    val config = RAGConfig().withWeightedScore(0.7, 0.3)
    config.fusionStrategy shouldBe a[FusionStrategy.WeightedScore]
    val ws = config.fusionStrategy.asInstanceOf[FusionStrategy.WeightedScore]
    ws.vectorWeight shouldBe 0.7
    ws.keywordWeight shouldBe 0.3
  }

  "RAGConfig.vectorOnly" should "set vector-only fusion" in {
    val config = RAGConfig().vectorOnly
    config.fusionStrategy shouldBe FusionStrategy.VectorOnly
  }

  "RAGConfig.keywordOnly" should "set keyword-only fusion" in {
    val config = RAGConfig().keywordOnly
    config.fusionStrategy shouldBe FusionStrategy.KeywordOnly
  }

  "RAGConfig.withTopK" should "set number of results" in {
    val config = RAGConfig().withTopK(10)
    config.topK shouldBe 10
  }

  // ==========================================================================
  // Reranking Configuration Tests
  // ==========================================================================

  "RAGConfig.withReranking" should "set reranking strategy" in {
    val config = RAGConfig().withReranking(RerankingStrategy.LLM)
    config.rerankingStrategy shouldBe RerankingStrategy.LLM
  }

  "RAGConfig.withCohereReranking" should "set Cohere reranking with default model" in {
    val config = RAGConfig().withCohereReranking()
    config.rerankingStrategy shouldBe a[RerankingStrategy.Cohere]
    config.rerankingStrategy.asInstanceOf[RerankingStrategy.Cohere].model shouldBe "rerank-english-v3.0"
  }

  it should "set Cohere reranking with custom model" in {
    val config = RAGConfig().withCohereReranking("rerank-multilingual-v3.0")
    config.rerankingStrategy.asInstanceOf[RerankingStrategy.Cohere].model shouldBe "rerank-multilingual-v3.0"
  }

  "RAGConfig.withLLMReranking" should "set LLM reranking" in {
    val config = RAGConfig().withLLMReranking
    config.rerankingStrategy shouldBe RerankingStrategy.LLM
  }

  "RAGConfig.withRerankTopK" should "set rerank candidates count" in {
    val config = RAGConfig().withRerankTopK(50)
    config.rerankTopK shouldBe 50
  }

  // ==========================================================================
  // Storage Configuration Tests
  // ==========================================================================

  "RAGConfig.withSQLite" should "set both paths" in {
    val config = RAGConfig().withSQLite("./data/vectors.db")
    config.vectorStorePath shouldBe Some("./data/vectors.db")
    config.keywordIndexPath shouldBe Some("./data/vectors-fts.db")
  }

  "RAGConfig.inMemory" should "clear storage paths" in {
    val config = RAGConfig()
      .withSQLite("./data/vectors.db")
      .inMemory
    config.vectorStorePath shouldBe None
    config.keywordIndexPath shouldBe None
    config.pgVectorConnectionString shouldBe None
  }

  "RAGConfig.withPgVector()" should "set default pgvector configuration" in {
    val config = RAGConfig().withPgVector()
    config.pgVectorConnectionString shouldBe Some("jdbc:postgresql://localhost:5432/postgres")
    config.pgVectorUser shouldBe Some("postgres")
    config.pgVectorPassword shouldBe Some("")
    config.pgVectorTableName shouldBe Some("vectors")
    config.vectorStorePath shouldBe None
  }

  "RAGConfig.withPgVector(tableName)" should "set pgvector with custom table" in {
    val config = RAGConfig().withPgVector("my_vectors")
    config.pgVectorConnectionString shouldBe Some("jdbc:postgresql://localhost:5432/postgres")
    config.pgVectorTableName shouldBe Some("my_vectors")
  }

  "RAGConfig.withPgVector(full)" should "set all pgvector parameters" in {
    val config = RAGConfig().withPgVector(
      connectionString = "jdbc:postgresql://myhost:5433/mydb",
      user = "myuser",
      password = "mypass",
      tableName = "embeddings"
    )
    config.pgVectorConnectionString shouldBe Some("jdbc:postgresql://myhost:5433/mydb")
    config.pgVectorUser shouldBe Some("myuser")
    config.pgVectorPassword shouldBe Some("mypass")
    config.pgVectorTableName shouldBe Some("embeddings")
  }

  "RAGConfig.withSQLite" should "clear pgvector settings" in {
    val config = RAGConfig()
      .withPgVector()
      .withSQLite("./data/vectors.db")
    config.pgVectorConnectionString shouldBe None
    config.vectorStorePath shouldBe Some("./data/vectors.db")
  }

  // ==========================================================================
  // Answer Generation Configuration Tests
  // ==========================================================================

  "RAGConfig.withSystemPrompt" should "set system prompt" in {
    val prompt = "You are a helpful assistant."
    val config = RAGConfig().withSystemPrompt(prompt)
    config.systemPrompt shouldBe Some(prompt)
  }

  // ==========================================================================
  // Document Loading Configuration Tests
  // ==========================================================================

  "RAGConfig.withDocuments(path)" should "add directory loader" in {
    val config = RAGConfig().withDocuments("./docs")
    config.documentLoaders should have size 1
    config.documentLoaders.head shouldBe a[DirectoryLoader]
  }

  "RAGConfig.withDocuments(loader)" should "add custom loader" in {
    val loader = DirectoryLoader("./custom")
    val config = RAGConfig().withDocuments(loader)
    config.documentLoaders should have size 1
    config.documentLoaders.head shouldBe loader
  }

  "RAGConfig.withDocuments(loaders)" should "add multiple loaders" in {
    val loaders = Seq(DirectoryLoader("./dir1"), DirectoryLoader("./dir2"))
    val config  = RAGConfig().withDocuments(loaders)
    config.documentLoaders should have size 2
  }

  "RAGConfig.withLoadingConfig" should "set loading config" in {
    val loadConfig = LoadingConfig(parallelism = 4, batchSize = 50, failFast = true)
    val config     = RAGConfig().withLoadingConfig(loadConfig)
    config.loadingConfig shouldBe loadConfig
  }

  "RAGConfig.failOnLoadError" should "set fail fast" in {
    val config = RAGConfig().failOnLoadError(fail = true)
    config.loadingConfig.failFast shouldBe true
  }

  "RAGConfig.withParallelism" should "set parallelism" in {
    val config = RAGConfig().withParallelism(8)
    config.loadingConfig.parallelism shouldBe 8
  }

  "RAGConfig.withBatchSize" should "set batch size" in {
    val config = RAGConfig().withBatchSize(100)
    config.loadingConfig.batchSize shouldBe 100
  }

  // ==========================================================================
  // Fluent API Chaining Tests
  // ==========================================================================

  "RAGConfig" should "support fluent chaining" in {
    val config = RAGConfig()
      .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
      .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
      .withRRF(60)
      .withTopK(10)
      .withCohereReranking()
      .withRerankTopK(50)
      .withSQLite("./rag.db")
      .withSystemPrompt("Be helpful")
      .withDocuments("./docs")
      .withParallelism(4)
      .withBatchSize(32)

    config.embeddingProvider shouldBe EmbeddingProvider.OpenAI
    config.embeddingModel shouldBe Some("text-embedding-3-large")
    config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
    config.chunkingConfig.targetSize shouldBe 800
    config.fusionStrategy shouldBe a[FusionStrategy.RRF]
    config.topK shouldBe 10
    config.rerankingStrategy shouldBe a[RerankingStrategy.Cohere]
    config.rerankTopK shouldBe 50
    config.vectorStorePath shouldBe Some("./rag.db")
    config.systemPrompt shouldBe Some("Be helpful")
    config.documentLoaders should have size 1
    config.loadingConfig.parallelism shouldBe 4
    config.loadingConfig.batchSize shouldBe 32
  }

  // ==========================================================================
  // Immutability Tests
  // ==========================================================================

  "RAGConfig" should "be immutable" in {
    val original = RAGConfig()
    val modified = original.withTopK(20)

    original.topK shouldBe 5
    modified.topK shouldBe 20
  }
}
