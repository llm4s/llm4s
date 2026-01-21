package org.llm4s.samples.rag

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.rag.{ EmbeddingProvider, RAG, RAGConfig }
import org.llm4s.rag.RAG.RAGConfigOps
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * RAG Builder API Example
 *
 * This example demonstrates the fluent RAG Builder API that simplifies
 * creating a complete RAG pipeline with minimal configuration.
 *
 * The builder provides:
 * - Sensible defaults (OpenAI embeddings, sentence chunking, RRF fusion)
 * - Fluent configuration methods (withEmbeddings, withChunking, etc.)
 * - Preset configurations (default, production, development)
 *
 * Usage:
 *   # Minimal (OpenAI embeddings)
 *   export OPENAI_API_KEY=sk-...
 *   sbt "samples/runMain org.llm4s.samples.rag.RAGBuilderExample"
 *
 *   # With answer generation
 *   export LLM_MODEL=openai/gpt-4o
 *   export OPENAI_API_KEY=sk-...
 *   sbt "samples/runMain org.llm4s.samples.rag.RAGBuilderExample"
 */
object RAGBuilderExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("RAG Builder API Example")
  logger.info("=" * 60)

  // ========== Example 1: Minimal Configuration ==========
  logger.info("--- Example 1: Minimal Configuration ---")
  logger.info("""
    |val rag = RAG.builder()
    |  .withEmbeddings(EmbeddingProvider.OpenAI)
    |  .build()
  """.stripMargin)

  val minimalConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.OpenAI)
    .tap(c => logger.info("Embedding provider: {}", c.embeddingProvider.name))
    .tap(c => logger.info("Chunking strategy: {}", c.chunkingStrategy))
    .tap(c => logger.info("Fusion strategy: {}", c.fusionStrategy))
    .tap(c => logger.info("Top K: {}", c.topK))

  // ========== Example 2: Full Customization ==========
  logger.info("--- Example 2: Full Customization ---")
  logger.info("""
    |val rag = RAG.builder()
    |  .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
    |  .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
    |  .withRRF(60)
    |  .withCohereReranking()
    |  .withSQLite("./rag.db")
    |  .withTopK(10)
    |  .build()
  """.stripMargin)

  val fullConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
    .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
    .withRRF(60)
    .withCohereReranking()
    .withSQLite("./rag.db")
    .withTopK(10)
    .tap(c => logger.info("Embedding model: {}", c.embeddingModel.getOrElse("default")))
    .tap(c => logger.info("Chunk size: {}", c.chunkingConfig.targetSize))
    .tap(c => logger.info("Chunk overlap: {}", c.chunkingConfig.overlap))
    .tap(c => logger.info("Storage path: {}", c.vectorStorePath.getOrElse("in-memory")))
    .tap(c => logger.info("Reranking: {}", c.rerankingStrategy))

  // ========== Example 3: Preset Configurations ==========
  logger.info("--- Example 3: Preset Configurations ---")

  logger.info("RAGConfig.development:")
  val devConfig = RAGConfig.development
    .tap(c => logger.info("  Storage: {}", c.vectorStorePath.getOrElse("in-memory")))
    .tap(c => logger.info("  Top K: {}", c.topK))

  logger.info("RAGConfig.production(\"/var/data/rag.db\"):")
  val prodConfig = RAGConfig
    .production("/var/data/rag.db")
    .tap(c => logger.info("  Storage: {}", c.vectorStorePath.getOrElse("in-memory")))
    .tap(c => logger.info("  Chunk size: {}", c.chunkingConfig.targetSize))

  // ========== Example 4: Different Embedding Providers ==========
  logger.info("--- Example 4: Different Embedding Providers ---")

  logger.info("Ollama (local, no API key):")
  val ollamaConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.Ollama, "nomic-embed-text")
    .tap(c => logger.info("  Provider: {}", c.embeddingProvider.name))
    .tap(c => logger.info("  Model: {}", c.embeddingModel.getOrElse("default")))

  logger.info("Voyage AI:")
  val voyageConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.Voyage, "voyage-3")
    .tap(c => logger.info("  Provider: {}", c.embeddingProvider.name))
    .tap(c => logger.info("  Model: {}", c.embeddingModel.getOrElse("default")))

  // ========== Example 5: Different Fusion Strategies ==========
  logger.info("--- Example 5: Fusion Strategies ---")

  logger.info("Vector-only search:")
  val vectorOnly = RAG
    .builder()
    .vectorOnly
    .tap(c => logger.info("  Strategy: {}", c.fusionStrategy))

  logger.info("Keyword-only search:")
  val keywordOnly = RAG
    .builder()
    .keywordOnly
    .tap(c => logger.info("  Strategy: {}", c.fusionStrategy))

  logger.info("Weighted score (70% vector, 30% keyword):")
  val weighted = RAG
    .builder()
    .withWeightedScore(0.7, 0.3)
    .tap(c => logger.info("  Strategy: {}", c.fusionStrategy))

  // ========== Example 6: Building and Using (Demo) ==========
  logger.info("--- Example 6: Building a Real RAG Pipeline ---")

  // Check if we have LLM configured for answer generation
  val llmResult = for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
  val hasLLM = llmResult.isRight

  if (hasLLM) {
    logger.info("LLM configured - building RAG with answer generation...")

    val result = for {
      llmClient <- llmResult
      rag <- RAG
        .builder()
        .withEmbeddings(EmbeddingProvider.OpenAI)
        .withTopK(3)
        .withLLM(llmClient)
        .build()
    } yield {
      logger.info("RAG pipeline created successfully!")
      logger.info("Document count: {}", rag.documentCount)
      logger.info("Chunk count: {}", rag.chunkCount)

      // Ingest sample text
      val ingestResult = rag.ingestText(
        """The RAG Builder API provides a fluent interface for creating
          |Retrieval-Augmented Generation pipelines. It supports multiple
          |embedding providers including OpenAI, Voyage AI, and Ollama.
          |The API uses immutable configuration with sensible defaults.""".stripMargin,
        "sample-doc-1"
      )
      ingestResult.foreach(count => logger.info("Ingested document with {} chunks", count))

      // Query
      val searchResult = rag.query("What embedding providers are supported?")
      searchResult.foreach { results =>
        logger.info("Search found {} results:", results.size)
        results.foreach(r => logger.info("  - Score: {}: {}...", r.score, r.content.take(80)))
      }

      rag.close()
    }

    result.left.foreach(error => logger.error("Error: {}", error))
  } else {
    logger.info("LLM not configured - skipping live demo")
    logger.info("Set LLM_MODEL and OPENAI_API_KEY (or equivalent) to run the full demo")
  }

  logger.info("=" * 60)
  logger.info("RAG Builder API Example Complete")
  logger.info("=" * 60)
}
