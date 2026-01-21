package org.llm4s.samples.rag

import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.llm4s.llmconnect.utils.ChunkingUtils
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import scala.util.chaining._

import java.io.File
import java.nio.file.{ Files, Path }

/**
 * Document Q&A RAG (Retrieval-Augmented Generation) Example
 *
 * This example demonstrates a complete RAG pipeline:
 * 1. Load documents from a directory (text/markdown files)
 * 2. Chunk documents into smaller pieces
 * 3. Generate embeddings and store in vector database
 * 4. Query with semantic search
 * 5. Augment LLM prompt with retrieved context
 * 6. Generate answers with source citations
 *
 * The demo uses MockEmbeddingService by default (no API key needed for embeddings).
 * For production, configure real embeddings via environment variables.
 *
 * Usage:
 *   # With mock embeddings (requires LLM API key for answer generation only)
 *   export LLM_MODEL=openai/gpt-4o
 *   export OPENAI_API_KEY=sk-...
 *   sbt "samples/runMain org.llm4s.samples.rag.DocumentQAExample"
 *
 *   # With real embeddings
 *   export LLM_EMBEDDING_MODEL=openai/text-embedding-3-small
 *   export LLM_MODEL=openai/gpt-4o
 *   export OPENAI_API_KEY=sk-...
 *   sbt "samples/runMain org.llm4s.samples.rag.DocumentQAExample ./my-docs"
 */
object DocumentQAExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 60)
  logger.info("Document Q&A - RAG Example")
  logger.info("=" * 60)

  // Configuration
  val config = RAGConfig(
    documentPath = Option(args).flatMap(_.headOption).getOrElse(defaultDocPath),
    chunkSize = 800,
    chunkOverlap = 150,
    topK = 4,
    supportedExtensions = Set(".txt", ".md")
  )

  // Main execution
  val result = runRAGDemo(config)

  result match {
    case Right(_) =>
      logger.info("=" * 60)
      logger.info("RAG Demo Complete!")
      logger.info("=" * 60)

    case Left(error) =>
      logger.error("Error: {}", error.formatted)
      logger.info("Troubleshooting:")
      logger.info("  - For LLM answers: Set LLM_MODEL and appropriate API key")
      logger.info("  - Example: export LLM_MODEL=openai/gpt-4o")
      logger.info("  - Example: export OPENAI_API_KEY=sk-...")
      System.exit(1)
  }

  // ============================================================
  // Main RAG Flow
  // ============================================================

  def runRAGDemo(config: RAGConfig): Result[Unit] = {
    // Create temporary database
    val dbPath: Path = Files.createTempFile("llm4s-rag-demo-", ".db")

    // Use real embeddings if LLM_EMBEDDING_MODEL is set, otherwise fall back to mock
    val embeddingServiceResult: Result[EmbeddingService] =
      Llm4sConfig
        .embeddings()
        .flatMap { case (provider, cfg) =>
          val dims     = scala.util.Try(ModelDimensionRegistry.getDimension(provider, cfg.model)).getOrElse(1536)
          val modelCfg = EmbeddingModelConfig(cfg.model, dims)
          EmbeddingClient.from(provider, cfg).map(client => LLMEmbeddingService(client, modelCfg))
        } match {
        case Right(realService) =>
          logger.info("Using LLMEmbeddingService (real embeddings)")
          Right(realService)
        case Left(_) =>
          logger.info("Using MockEmbeddingService (1536 dimensions)")
          logger.info("Tip: Set LLM_EMBEDDING_MODEL for real embeddings")
          Right(MockEmbeddingService(dimensions = 1536))
      }

    for {
      embeddingService <- embeddingServiceResult
      // Create vector store
      store <- VectorMemoryStore(dbPath.toString, embeddingService, MemoryStoreConfig.default)
        .tap(_ => logger.info("Database: {}", dbPath))

      // Step 1: Ingest documents
      _ = logger.info("-" * 40)
      _ = logger.info("Step 1: Ingesting Documents")
      _ = logger.info("-" * 40)
      chunkCount <- ingestPath(store, config.documentPath, config)
      _ = logger.info("Ingested {} chunks from documents", chunkCount)

      stats <- store.vectorStats
      _ = logger.info("Store stats: {} memories, {} embedded", stats.totalMemories, stats.embeddedMemories)

      // Step 2: Connect to LLM for answer generation
      _ = logger.info("-" * 40)
      _ = logger.info("Step 2: Connecting to LLM")
      _ = logger.info("-" * 40)
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      _ = logger.info("LLM client connected successfully")

      // Step 3: Run sample queries
      _ = logger.info("-" * 40)
      _ = logger.info("Step 3: Running Sample Queries")
      _ = logger.info("-" * 40)
      _ <- runSampleQueries(store, client, config)

      // Cleanup
      _ = store.close()
      _ = Files.deleteIfExists(dbPath)

    } yield ()
  }

  // ============================================================
  // Document Ingestion
  // ============================================================

  def ingestPath(store: VectorMemoryStore, path: String, config: RAGConfig): Result[Int] = {
    val file = new File(path)
    if (!file.exists()) {
      Left(ProcessingError("ingestion", s"Path does not exist: $path"))
    } else if (file.isDirectory) {
      ingestDirectory(store, path, config)
    } else {
      ingestFile(store, path, config)
    }
  }

  def ingestDirectory(store: VectorMemoryStore, dirPath: String, config: RAGConfig): Result[Int] = {
    val dir = new File(dirPath)
    val files = Option(dir.listFiles())
      .map(_.toSeq)
      .getOrElse(Seq.empty)
      .filter(f => f.isFile && config.supportedExtensions.exists(ext => f.getName.toLowerCase.endsWith(ext)))

    if (files.isEmpty) {
      logger.warn("  Warning: No supported files found in {}", dirPath)
      logger.info("  Supported extensions: {}", config.supportedExtensions.mkString(", "))
      Right(0)
    } else {
      logger.info("  Found {} documents to ingest", files.size)
      files.foldLeft[Result[Int]](Right(0)) { (acc, file) =>
        for {
          count <- acc
          added <- ingestFile(store, file.getAbsolutePath, config)
        } yield count + added
      }
    }
  }

  def ingestFile(store: VectorMemoryStore, filePath: String, config: RAGConfig): Result[Int] = {
    val fileName = new File(filePath).getName
    logger.info("  Processing: {}", fileName)

    UniversalExtractor.extract(filePath) match {
      case Left(e) =>
        logger.warn("    Warning: Failed to extract {}: {}", fileName, e.message)
        Right(0) // Skip failed files, don't fail entire ingestion

      case Right(text) =>
        val chunks = ChunkingUtils.chunkText(text, config.chunkSize, config.chunkOverlap)
        logger.info("    Created {} chunks", chunks.size)

        val storeResults = chunks.zipWithIndex.foldLeft[Result[MemoryStore]](Right(store)) {
          case (Right(s), (chunk, idx)) =>
            val memory = Memory.fromKnowledge(
              content = chunk,
              source = fileName,
              chunkIndex = Some(idx)
            )
            s.store(memory)
          case (left, _) => left
        }

        storeResults.map(_ => chunks.size)
    }
  }

  // ============================================================
  // Query Processing
  // ============================================================

  def runSampleQueries(store: VectorMemoryStore, client: LLMClient, config: RAGConfig): Result[Unit] = {
    val queries = Seq(
      "What is Scala and what are its key features?",
      "How does LLM4S handle errors?",
      "Explain the concept of pure functions in functional programming."
    )

    queries.foreach { query =>
      logger.info("=" * 50)
      logger.info("Q: {}", query)
      logger.info("=" * 50)

      queryWithContext(store, client, query, config) match {
        case Right(response) =>
          logger.info("Answer:\n{}", response.answer)
          logger.info("{}", formatCitations(response.sources))

        case Left(error) =>
          logger.error("Error generating answer: {}", error.formatted)
      }
    }

    Right(())
  }

  def queryWithContext(
    store: VectorMemoryStore,
    client: LLMClient,
    query: String,
    config: RAGConfig
  ): Result[RAGResponse] =
    for {
      // Retrieve relevant chunks
      scoredMemories <- store
        .search(query, config.topK, MemoryFilter.ByType(MemoryType.Knowledge))
        .tap {
          case Right(chunks) =>
            logger.info("  Retrieved {} relevant chunks", chunks.size)
          case Left(err) =>
            logger.warn("  Failed to retrieve relevant chunks: {}", err.message)
        }

      // Build context from retrieved chunks
      context = formatContext(scoredMemories)

      // Build RAG prompt
      systemPrompt = buildSystemPrompt()
      userPrompt   = buildUserPrompt(query, context)

      // Generate answer
      conversation = Conversation(
        Seq(
          SystemMessage(systemPrompt),
          UserMessage(userPrompt)
        )
      )
      completion <- client.complete(conversation)

    } yield RAGResponse(
      answer = completion.message.content,
      sources = scoredMemories.map(sm =>
        Citation(
          content = sm.memory.content.take(100) + (if (sm.memory.content.length > 100) "..." else ""),
          source = sm.memory.getMetadata("source").getOrElse("unknown"),
          chunkIndex = sm.memory.getMetadata("chunk_index").flatMap(s => scala.util.Try(s.toInt).toOption),
          score = sm.score
        )
      )
    )

  def buildSystemPrompt(): String =
    """You are a helpful assistant that answers questions based on the provided document context.

Instructions:
- Answer based ONLY on the information in the provided context
- If the context doesn't contain enough information, say so clearly
- Cite your sources by mentioning the document names when relevant
- Be concise but thorough in your answers
- Do not make up information that isn't in the context"""

  def buildUserPrompt(query: String, context: String): String =
    s"""Here is relevant context from the documents:

$context

Based on the above context, please answer the following question:

$query"""

  // ============================================================
  // Formatting Helpers
  // ============================================================

  def formatContext(memories: Seq[ScoredMemory]): String =
    memories.zipWithIndex
      .map { case (sm, idx) =>
        val source = sm.memory.getMetadata("source").getOrElse("unknown")
        val chunkInfo = sm.memory
          .getMetadata("chunk_index")
          .map(c => s" (chunk $c)")
          .getOrElse("")
        s"""--- Source ${idx + 1}: $source$chunkInfo ---
${sm.memory.content}
"""
      }
      .mkString("\n")

  def formatCitations(citations: Seq[Citation]): String = {
    if (citations.isEmpty) return "No sources found."

    "Sources:\n" + citations.zipWithIndex
      .map { case (c, idx) =>
        val chunkInfo = c.chunkIndex.map(i => s" (chunk $i)").getOrElse("")
        f"  [${idx + 1}] ${c.source}$chunkInfo - relevance: ${c.score}%.2f"
      }
      .mkString("\n")
  }

  // ============================================================
  // Configuration and Types
  // ============================================================

  case class RAGConfig(
    documentPath: String,
    chunkSize: Int = 800,
    chunkOverlap: Int = 150,
    topK: Int = 4,
    supportedExtensions: Set[String] = Set(".txt", ".md")
  )

  case class Citation(
    content: String,
    source: String,
    chunkIndex: Option[Int],
    score: Double
  )

  case class RAGResponse(
    answer: String,
    sources: Seq[Citation]
  )

  // Default path to bundled sample documents
  private def defaultDocPath: String = {
    // Try common locations for sample documents
    val candidates = Seq(
      "modules/samples/src/main/resources/sample-docs",
      "src/main/resources/sample-docs",
      "sample-docs"
    )

    candidates.find(p => new File(p).isDirectory).getOrElse {
      // Try to extract from classpath resource as fallback
      val resourceUrl = getClass.getClassLoader.getResource("sample-docs")
      if (resourceUrl != null && resourceUrl.getProtocol == "file") {
        scala.util.Try(new File(resourceUrl.toURI).getAbsolutePath).getOrElse(candidates.head)
      } else {
        candidates.head // Default to first candidate (will show helpful error)
      }
    }
  }
}
