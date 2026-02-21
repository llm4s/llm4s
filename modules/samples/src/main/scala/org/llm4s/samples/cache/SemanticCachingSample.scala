package org.llm4s.samples.cache

import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.llmconnect.config.{ OpenAIConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.trace.{ ConsoleTracing, Tracing }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

/**
 * Sample demonstrating semantic LLM response caching.
 *
 * This example shows how to:
 * 1. Configure semantic caching with similarity threshold and TTL
 * 2. Observe cache hits for similar queries
 * 3. Observe cache misses for different queries or expired entries
 *
 * Prerequisites:
 * - Set OPENAI_API_KEY environment variable
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.cache.SemanticCachingSample"
 */
object SemanticCachingSample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  def runDemo(): Unit = {
    // Check for API key
    val apiKey = sys.env.get("OPENAI_API_KEY") match {
      case Some(key) => key
      case None =>
        logger.error("OPENAI_API_KEY environment variable not set")
        return
    }

    println("=== Semantic Caching Demo ===\n")

    // Configure tracing to see cache hits/misses
    val tracing: Tracing = new ConsoleTracing()

    // Create base LLM client
    val openAIConfig = OpenAIConfig.fromValues(
      modelName = "gpt-4o-mini",
      apiKey = apiKey,
      organization = None,
      baseUrl = "https://api.openai.com"
    )
    val baseLLMClient: LLMClient = OpenAIClient(openAIConfig) match {
      case Right(client) => client
      case Left(err) =>
        logger.error(s"Failed to initialize OpenAI client: ${err.message}")
        return
    }

    // Create embedding client for semantic similarity
    val embeddingConfig = EmbeddingProviderConfig(
      baseUrl = "https://api.openai.com",
      model = "text-embedding-3-small",
      apiKey = apiKey
    )
    val embeddingClient = EmbeddingClient.from("openai", embeddingConfig) match {
      case Right(client) => client
      case Left(err) =>
        logger.error(s"Failed to initialize embedding client: ${err.message}")
        return
    }
    val embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536)

    // Configure cache: 95% similarity threshold, 5 minute TTL, max 100 entries
    val cacheConfig = CacheConfig
      .create(
        similarityThreshold = 0.95,
        ttl = 5.minutes,
        maxSize = 100
      ) match {
      case Right(config) => config
      case Left(err) =>
        logger.error(s"Failed to create cache config: ${err.message}")
        return
    }

    // Wrap base client with caching
    val cachingClient = new CachingLLMClient(
      baseClient = baseLLMClient,
      embeddingClient = embeddingClient,
      embeddingModel = embeddingModel,
      config = cacheConfig,
      tracing = tracing
    )

    println("Configuration:")
    println(s"  Similarity Threshold: ${cacheConfig.similarityThreshold}")
    println(s"  TTL: ${cacheConfig.ttl}")
    println(s"  Max Size: ${cacheConfig.maxSize}\n")

    // Demo 1: Cache miss then hit
    println("--- Demo 1: Identical queries (should hit cache) ---")
    val query1 = "What is the capital of France?"
    val conv1 = Conversation.userOnly(query1) match {
      case Right(conv) => conv
      case Left(err) =>
        logger.error(s"Failed to create conversation: ${err.message}")
        return
    }

    println(s"Query 1: $query1")
    cachingClient.complete(conv1) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    println(s"Query 2 (same): $query1")
    cachingClient.complete(conv1) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    // Demo 2: Similar query (should hit cache if similarity > threshold)
    println("--- Demo 2: Similar query ---")
    val query2 = "Tell me the capital city of France"
    val conv2 = Conversation.userOnly(query2) match {
      case Right(conv) => conv
      case Left(err) =>
        logger.error(s"Failed to create conversation: ${err.message}")
        return
    }

    println(s"Query 3 (similar): $query2")
    cachingClient.complete(conv2) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    // Demo 3: Different query (should miss cache)
    println("--- Demo 3: Different query (should miss cache) ---")
    val query3 = "What is the capital of Germany?"
    val conv3 = Conversation.userOnly(query3) match {
      case Right(conv) => conv
      case Left(err) =>
        logger.error(s"Failed to create conversation: ${err.message}")
        return
    }

    println(s"Query 4 (different): $query3")
    cachingClient.complete(conv3) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    // Demo 4: Options mismatch (should miss cache)
    println("--- Demo 4: Same query, different options (should miss cache) ---")
    println(s"Query 5 (same as #1, but temperature=0.9): $query1")
    cachingClient.complete(conv1, CompletionOptions(temperature = 0.9)) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    println("=== Demo Complete ===")
    println("\nCheck the console output above to see:")
    println("  - CACHE MISS (first query)")
    println("  - CACHE HIT (identical query)")
    println("  - CACHE HIT or MISS (similar query, depends on embedding similarity)")
    println("  - CACHE MISS (different query)")
    println("  - CACHE MISS (options mismatch)")

    // Cleanup
    cachingClient.close()
  }

  runDemo()
}
