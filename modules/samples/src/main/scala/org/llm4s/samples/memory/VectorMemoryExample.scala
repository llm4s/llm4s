package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.slf4j.LoggerFactory
import scala.util.chaining._

import java.nio.file.{ Files, Path }

/**
 * Example demonstrating vector-based semantic memory search.
 *
 * This example shows how to:
 * - Create a vector memory store with embeddings
 * - Perform semantic (meaning-based) search
 * - Compare semantic search with keyword search
 * - Batch embed memories
 * - Monitor embedding coverage
 *
 * Note: This example uses MockEmbeddingService for demonstration.
 * In production, wire LLMEmbeddingService using Llm4sConfig + EmbeddingClient.from(...)
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.memory.VectorMemoryExample"
 */
object VectorMemoryExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("Vector Memory Store Example")
    logger.info("=" * 60)

    // Create a temporary database file
    val dbPath: Path = Files.createTempFile("llm4s-vector-memory-", ".db")
    logger.info("Database location: {}", dbPath)

    // Use mock embedding service for this example
    // In production, construct LLMEmbeddingService using Llm4sConfig and EmbeddingClient.from(...)
    val embeddingService = MockEmbeddingService(dimensions = 1536)
    logger.info("Using embedding service with {} dimensions", embeddingService.dimensions)

    // ============================================================
    // Part 1: Create store and populate with memories
    // ============================================================
    logger.info("--- Part 1: Creating and Populating Vector Store ---")

    val createResult = for {
      store <- VectorMemoryStore(dbPath.toString, embeddingService, MemoryStoreConfig.default)

      // Store knowledge about programming languages
      _ = logger.info("Storing programming language knowledge...")
      _ <- store.store(
        Memory.fromKnowledge(
          "Python is an interpreted high-level programming language known for its simplicity and readability.",
          source = "lang-docs",
          chunkIndex = Some(1)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Scala combines object-oriented and functional programming paradigms on the JVM.",
          source = "lang-docs",
          chunkIndex = Some(2)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Rust is a systems programming language focused on safety, speed, and concurrency.",
          source = "lang-docs",
          chunkIndex = Some(3)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "JavaScript is the primary language for web development, running in browsers and Node.js.",
          source = "lang-docs",
          chunkIndex = Some(4)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Haskell is a purely functional programming language with strong static typing.",
          source = "lang-docs",
          chunkIndex = Some(5)
        )
      )

      // Store some related concepts
      _ = logger.info("Storing related concepts...")
      _ <- store.store(
        Memory.fromKnowledge(
          "Functional programming emphasizes immutability, pure functions, and declarative code.",
          source = "fp-guide"
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Object-oriented programming uses classes and objects to model real-world entities.",
          source = "oop-guide"
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Type systems help catch errors at compile time and improve code reliability.",
          source = "types-guide"
        )
      )

      count <- store.count()
      _ = logger.info("Total memories stored: {}", count)

    } yield store

    createResult match {
      case Left(error) =>
        logger.error("Error creating store: {}", error)
        System.exit(1)

      case Right(store) =>
        // ============================================================
        // Part 2: Semantic Search Demonstration
        // ============================================================
        logger.info("--- Part 2: Semantic Search ---")

        // Search for a concept using different phrasings
        val queries = Seq(
          "language for writing safe concurrent code",
          "programming with no side effects",
          "language that runs in web browsers",
          "static type checking benefits"
        )

        queries.foreach { query =>
          logger.info("Query: \"{}\"", query)
          store.search(query, 3) match {
            case Right(results) =>
              results.zipWithIndex.foreach { case (scored, idx) =>
                logger.info("  {}. [{}] {}...", idx + 1, scored.score, scored.memory.content.take(70))
              }
            case Left(error) =>
              logger.error("  Search error: {}", error)
          }
        }

        // ============================================================
        // Part 3: Vector Statistics
        // ============================================================
        logger.info("--- Part 3: Vector Statistics ---")

        store.vectorStats match {
          case Right(stats) =>
            logger.info("Total memories: {}", stats.totalMemories)
            logger.info("Embedded memories: {}", stats.embeddedMemories)
            logger.info("Coverage: {}%", (stats.embeddedMemories * 100.0 / stats.totalMemories).toInt)
            logger.info("Embedding dimensions: {}", stats.embeddingDimensions.mkString(", "))
          case Left(error) =>
            logger.error("Stats error: {}", error)
        }

        // ============================================================
        // Part 4: Filtered Semantic Search
        // ============================================================
        logger.info("--- Part 4: Filtered Semantic Search ---")

        // Add some memories of different types
        val conversationResult = for {
          _ <- store.store(Memory.fromConversation("What language should I learn?", "user", Some("conv-1")))
          _ <- store.store(
            Memory.fromConversation(
              "It depends on your goals. Python is great for beginners.",
              "assistant",
              Some("conv-1")
            )
          )
          _ <- store.store(Memory.userFact("User is interested in data science", Some("user-1")))
        } yield ()

        conversationResult match {
          case Left(error) =>
            logger.error("Error storing conversations: {}", error)

          case Right(_) =>
            // Search only in knowledge base
            logger.info("Searching only Knowledge memories for 'beginner friendly':")
            store.search("beginner friendly", 3, filter = MemoryFilter.ByType(MemoryType.Knowledge)) match {
              case Right(res) =>
                res.foreach(scored => logger.info("  [{}] {}...", scored.score, scored.memory.content.take(60)))
              case Left(err) =>
                logger.error("  Error: {}", err)
            }

            // Search only in conversations
            logger.info("Searching only Conversation memories:")
            store.search(
              "language recommendation",
              3,
              filter = MemoryFilter.ByType(MemoryType.Conversation)
            ) match {
              case Right(res) =>
                if (res.isEmpty) logger.info("  (no matches)")
                else res.foreach(scored => logger.info("  [{}] {}...", scored.score, scored.memory.content.take(60)))
              case Left(err) =>
                logger.error("  Error: {}", err)
            }
        }

        // ============================================================
        // Part 5: Batch Embedding
        // ============================================================
        logger.info("--- Part 5: Batch Embedding Demo ---")

        // Add memories without embedding (simulating import scenario)
        val batchResult = for {
          // Clear and recreate to demonstrate batch embedding
          _ <- store.clear()

          // Store without auto-embedding by using SQLite store directly
          // For this demo, we'll add memories normally and show stats
          _ <- store.store(Memory.fromKnowledge("Memory 1: Scala features", "batch"))
          _ <- store.store(Memory.fromKnowledge("Memory 2: Functional concepts", "batch"))
          _ <- store.store(Memory.fromKnowledge("Memory 3: Type systems", "batch"))

          _ <- store.vectorStats.tap {
            case Right(s) => logger.info("After storing: {}/{} embedded", s.embeddedMemories, s.totalMemories)
            case Left(e)  => logger.error("Stats failed: {}", e.message)
          }

          // Batch embed any unembedded memories
          _ <- store.embedAll(10).tap {
            case Right(c) => logger.info("Batch embedded: {} memories", c)
            case Left(e)  => logger.error("Embed all failed: {}", e.message)
          }

          _ <- store.vectorStats.tap {
            case Right(s) => logger.info("Final stats: {}/{} embedded", s.embeddedMemories, s.totalMemories)
            case Left(e)  => logger.error("Stats failed: {}", e.message)
          }

        } yield ()

        batchResult.left.foreach(error => logger.error("Batch error: {}", error))

        // ============================================================
        // Part 6: Using with SimpleMemoryManager
        // ============================================================
        logger.info("--- Part 6: Using with SimpleMemoryManager ---")

        // Recreate store for manager demo
        VectorMemoryStore(dbPath.toString, embeddingService, MemoryStoreConfig.default) match {
          case Right(vectorStore) =>
            vectorStore.clear()

            val config  = MemoryManagerConfig(defaultImportance = 0.5)
            val manager = SimpleMemoryManager.withStore(vectorStore, config)

            val managerResult = for {
              m1 <- manager.recordKnowledge(
                "llm4s provides a unified API for multiple LLM providers",
                "readme",
                Map("version" -> "0.1")
              )
              m2 <- m1.recordKnowledge(
                "The agent framework supports tool calling and multi-turn conversations",
                "readme",
                Map("section" -> "agents")
              )
              m3 <- m2.recordKnowledge(
                "Vector memory enables semantic search over stored knowledge",
                "readme",
                Map("section" -> "memory")
              )
              context <- m3.getRelevantContext("How does the memory system work?")
            } yield context

            managerResult match {
              case Right(context) =>
                logger.info("Relevant context for 'How does the memory system work?':")
                logger.info("  {}", context)
              case Left(error) =>
                logger.error("Manager error: {}", error)
            }

            vectorStore.close()

          case Left(error) =>
            logger.error("Error: {}", error)
        }

        // Clean up original store
        store.close()
    }

    // Cleanup
    logger.info("--- Cleanup ---")
    Files.deleteIfExists(dbPath)
    logger.info("Deleted temporary database: {}", dbPath)

    logger.info("=" * 60)
    logger.info("Vector Memory Example Complete!")
    logger.info("=" * 60)

    // ============================================================
    // Production Usage Notes
    // ============================================================
    logger.info(
      """
        |=== Production Usage Notes ===
        |
        |To use real embeddings in production:
        |
        |  // 1. Configure embeddings provider and model in application.conf / reference.conf:
        |  //    llm4s.embeddings.provider = "openai"        # or "voyage"
        |  //    llm4s.embeddings.openai.baseUrl = "https://api.openai.com/v1"
        |  //    llm4s.embeddings.openai.model   = "text-embedding-3-small"
        |  //
        |  // 2. Load typed config and wire the client + service explicitly:
        |  //
        |  //  import org.llm4s.config.Llm4sConfig
        |  //  import org.llm4s.llmconnect.EmbeddingClient
        |  //  import org.llm4s.llmconnect.config.EmbeddingModelConfig
        |  //
        |  //  val result = for {
        |  //    (providerName, providerCfg) <- Llm4sConfig.embeddings()
        |  //    client                      <- EmbeddingClient.from(providerName, providerCfg)
        |  //    textModelSettings           <- Llm4sConfig.textEmbeddingModel()
        |  //    modelConfig = EmbeddingModelConfig(
        |  //                     name = textModelSettings.modelName,
        |  //                     dimensions = textModelSettings.dimensions
        |  //                    )
        |  //    embeddingService = LLMEmbeddingService(client, modelConfig)
        |  //    store             <- VectorMemoryStore(dbPath, embeddingService, config)
        |  //  } yield store
        |
        |Supported embedding models include:
        |  - OpenAI: text-embedding-ada-002, text-embedding-3-small, text-embedding-3-large
        |  - VoyageAI: voyage-2, voyage-large-2, voyage-code-2
        |
      """.stripMargin
    )
  }
}
