package org.llm4s.samples.cache

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, EmbeddingModelConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.llmconnect.config.{ OpenAIConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.ConsoleTracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Using

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

  private def runDemo(): Result[Unit] =
    for {
      registryService <- Llm4sConfig.modelRegistryService()
      result <- {
        given ModelRegistryService  = registryService
        given ContextWindowResolver = ContextWindowResolver(registryService)
        for {
          apiKey          <- loadApiKey()
          baseLLMClient   <- createBaseClient(apiKey)
          embeddingClient <- createEmbeddingClient(apiKey)
          cacheConfig     <- createCacheConfig()
          conv1           <- Conversation.userOnly("What is the capital of France?")
          conv2           <- Conversation.userOnly("Tell me the capital city of France")
          conv3           <- Conversation.userOnly("What is the capital of Germany?")
        } yield {
          val tracing        = new ConsoleTracing()
          val embeddingModel = EmbeddingModelConfig("text-embedding-3-small", 1536)
          Using.resource(
            new CachingLLMClient(
              baseClient = baseLLMClient,
              embeddingClient = embeddingClient,
              embeddingModel = embeddingModel,
              config = cacheConfig,
              tracing = tracing
            )
          )(cachingClient => runScenarios(cachingClient, cacheConfig, conv1, conv2, conv3))
        }
      }
    } yield result

  private def loadApiKey(): Result[String] =
    Llm4sConfig.defaultProvider().flatMap {
      case cfg: OpenAIConfig => Right(cfg.apiKey)
      case _ =>
        Left(ConfigurationError("This sample requires an OpenAI provider (set LLM_MODEL=openai/<model>)"))
    }

  private def createBaseClient(
    apiKey: String
  )(using ContextWindowResolver, ModelRegistryService): Result[LLMClient] = {
    println("=== Semantic Caching Demo ===\n")
    val openAIConfig = OpenAIConfig.fromValues(
      modelName = "gpt-4o-mini",
      apiKey = apiKey,
      organization = None,
      baseUrl = "https://api.openai.com"
    )
    OpenAIClient(openAIConfig)
  }

  private def createEmbeddingClient(apiKey: String)(using ModelRegistryService): Result[EmbeddingClient] = {
    val embeddingConfig = EmbeddingProviderConfig(
      baseUrl = "https://api.openai.com",
      model = "text-embedding-3-small",
      apiKey = apiKey
    )
    EmbeddingClient.from("openai", embeddingConfig)
  }

  private def createCacheConfig(): Result[CacheConfig] =
    CacheConfig.create(
      similarityThreshold = 0.95,
      ttl = 5.minutes,
      maxSize = 100
    )

  private def runScenarios(
    cachingClient: CachingLLMClient,
    cacheConfig: CacheConfig,
    conv1: Conversation,
    conv2: Conversation,
    conv3: Conversation
  ): Unit = {
    println("Configuration:")
    println(s"  Similarity Threshold: ${cacheConfig.similarityThreshold}")
    println(s"  TTL: ${cacheConfig.ttl}")
    println(s"  Max Size: ${cacheConfig.maxSize}\n")

    // Demo 1: Cache miss then hit
    println("--- Demo 1: Identical queries (should hit cache) ---")
    val query1 = conv1.messages.last.content

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
    val query2 = conv2.messages.last.content

    println(s"Query 3 (similar): $query2")
    cachingClient.complete(conv2) match {
      case Right(completion) => println(s"Response: ${completion.content}\n")
      case Left(error)       => println(s"Error: ${error.message}\n")
    }

    // Demo 3: Different query (should miss cache)
    println("--- Demo 3: Different query (should miss cache) ---")
    val query3 = conv3.messages.last.content

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
  }

  runDemo().left.foreach(err => logger.error("Semantic caching demo failed: {}", err.formatted))
}
