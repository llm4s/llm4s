package org.llm4s.samples.rag

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.rag.{ EmbeddingProvider, RAG, RAGConfig }
import org.llm4s.rag.RAG.RAGConfigOps

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

  println("=" * 60)
  println("RAG Builder API Example")
  println("=" * 60)

  // ========== Example 1: Minimal Configuration ==========
  println("\n--- Example 1: Minimal Configuration ---")
  println("""
    |val rag = RAG.builder()
    |  .withEmbeddings(EmbeddingProvider.OpenAI)
    |  .build()
  """.stripMargin)

  val minimalConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.OpenAI)

  println(s"Embedding provider: ${minimalConfig.embeddingProvider.name}")
  println(s"Chunking strategy: ${minimalConfig.chunkingStrategy}")
  println(s"Fusion strategy: ${minimalConfig.fusionStrategy}")
  println(s"Top K: ${minimalConfig.topK}")

  // ========== Example 2: Full Customization ==========
  println("\n--- Example 2: Full Customization ---")
  println("""
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

  println(s"Embedding model: ${fullConfig.embeddingModel.getOrElse("default")}")
  println(s"Chunk size: ${fullConfig.chunkingConfig.targetSize}")
  println(s"Chunk overlap: ${fullConfig.chunkingConfig.overlap}")
  println(s"Storage path: ${fullConfig.vectorStorePath.getOrElse("in-memory")}")
  println(s"Reranking: ${fullConfig.rerankingStrategy}")

  // ========== Example 3: Preset Configurations ==========
  println("\n--- Example 3: Preset Configurations ---")

  println("\nRAGConfig.development:")
  val devConfig = RAGConfig.development
  println(s"  Storage: ${devConfig.vectorStorePath.getOrElse("in-memory")}")
  println(s"  Top K: ${devConfig.topK}")

  println("\nRAGConfig.production(\"/var/data/rag.db\"):")
  val prodConfig = RAGConfig.production("/var/data/rag.db")
  println(s"  Storage: ${prodConfig.vectorStorePath.getOrElse("in-memory")}")
  println(s"  Chunk size: ${prodConfig.chunkingConfig.targetSize}")

  // ========== Example 4: Different Embedding Providers ==========
  println("\n--- Example 4: Different Embedding Providers ---")

  println("\nOllama (local, no API key):")
  val ollamaConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.Ollama, "nomic-embed-text")
  println(s"  Provider: ${ollamaConfig.embeddingProvider.name}")
  println(s"  Model: ${ollamaConfig.embeddingModel.getOrElse("default")}")

  println("\nVoyage AI:")
  val voyageConfig = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.Voyage, "voyage-3")
  println(s"  Provider: ${voyageConfig.embeddingProvider.name}")
  println(s"  Model: ${voyageConfig.embeddingModel.getOrElse("default")}")

  // ========== Example 5: Different Fusion Strategies ==========
  println("\n--- Example 5: Fusion Strategies ---")

  println("\nVector-only search:")
  val vectorOnly = RAG.builder().vectorOnly
  println(s"  Strategy: ${vectorOnly.fusionStrategy}")

  println("\nKeyword-only search:")
  val keywordOnly = RAG.builder().keywordOnly
  println(s"  Strategy: ${keywordOnly.fusionStrategy}")

  println("\nWeighted score (70% vector, 30% keyword):")
  val weighted = RAG.builder().withWeightedScore(0.7, 0.3)
  println(s"  Strategy: ${weighted.fusionStrategy}")

  // ========== Example 6: Building and Using (Demo) ==========
  println("\n--- Example 6: Building a Real RAG Pipeline ---")

  // Check if we have LLM configured for answer generation
  val llmResult = for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
  val hasLLM = llmResult.isRight

  if (hasLLM) {
    println("LLM configured - building RAG with answer generation...")

    val result = for {
      llmClient <- llmResult
      rag <- RAG
        .builder()
        .withEmbeddings(EmbeddingProvider.OpenAI)
        .withTopK(3)
        .withLLM(llmClient)
        .build()
    } yield {
      println(s"RAG pipeline created successfully!")
      println(s"Document count: ${rag.documentCount}")
      println(s"Chunk count: ${rag.chunkCount}")

      // Ingest sample text
      val ingestResult = rag.ingestText(
        """The RAG Builder API provides a fluent interface for creating
          |Retrieval-Augmented Generation pipelines. It supports multiple
          |embedding providers including OpenAI, Voyage AI, and Ollama.
          |The API uses immutable configuration with sensible defaults.""".stripMargin,
        "sample-doc-1"
      )
      ingestResult.foreach(count => println(s"Ingested document with $count chunks"))

      // Query
      val searchResult = rag.query("What embedding providers are supported?")
      searchResult.foreach { results =>
        println(s"\nSearch found ${results.size} results:")
        results.foreach(r => println(s"  - Score: ${r.score}: ${r.content.take(80)}..."))
      }

      rag.close()
    }

    result.left.foreach(error => println(s"Error: $error"))
  } else {
    println("LLM not configured - skipping live demo")
    println("Set LLM_MODEL and OPENAI_API_KEY (or equivalent) to run the full demo")
  }

  println("\n" + "=" * 60)
  println("RAG Builder API Example Complete")
  println("=" * 60)
}
