package org.llm4s.samples.rag

import org.llm4s.rag.loader._
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating the DocumentLoader API for RAG pipelines.
 *
 * Shows how to:
 * - Load documents from various sources (files, URLs, text)
 * - Use document loaders with RAG.builder()
 * - Perform incremental sync operations
 * - Combine multiple loaders
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.DocumentLoaderExample"
 * }}}
 */
object DocumentLoaderExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Document Loader Example ===")

  // ========== 1. Basic TextLoader Usage ==========
  logger.info("1. Creating documents with TextLoader...")

  val textLoader = TextLoader
    .builder()
    .add("doc-1", "Scala is a modern programming language that combines object-oriented and functional programming.")
    .add("doc-2", "LLM4S is a framework for building LLM-powered applications in Scala.")
    .add("doc-3", "RAG (Retrieval-Augmented Generation) improves LLM responses by providing relevant context.")
    .build()
    .tap(l => logger.info("   Created loader with {} documents", l.estimatedCount.getOrElse("?")))

  // ========== 2. FileLoader and DirectoryLoader ==========
  logger.info("2. FileLoader and DirectoryLoader...")

  // Single file loader
  val fileLoader = FileLoader("./README.md")
    .tap(l => logger.info("   FileLoader: {}", l.description))

  // Directory loader with filtering
  val dirLoader = DirectoryLoader("./docs")
    .withExtensions(Set(".md", ".txt"))
    .withRecursive(true)
    .tap(l => logger.info("   DirectoryLoader: {}", l.description))

  // ========== 3. Combining Loaders ==========
  logger.info("3. Combining multiple loaders...")

  val combinedLoader = (textLoader ++ fileLoader)
    .tap(l => logger.info("   Combined: {}", l.description))

  // Using DocumentLoaders combinators
  val filteredLoader = DocumentLoaders
    .filter(textLoader)(doc => doc.content.contains("Scala"))
    .tap(l => logger.info("   Filtered: {}", l.description))

  // ========== 4. Build-Time Loading ==========
  logger.info("4. Build-time loading with RAG.builder()...")
  logger.info("   (Requires embedding provider environment variables)")

  // Example of build-time loading:
  // val rag = RAG.builder()
  //   .withEmbeddings(EmbeddingProvider.OpenAI)
  //   .withDocuments(textLoader)
  //   .withDocuments("./docs")  // Can also use path strings
  //   .build()
  //   .toOption.get

  // ========== 5. Ingest-Time Loading ==========
  logger.info("5. Ingest-time loading...")
  logger.info("   Example: rag.ingest(DirectoryLoader(\"./new-docs\"))")

  // val stats = rag.ingest(DirectoryLoader("./new-docs"))
  // stats match {
  //   case Right(s) =>
  //     logger.info("Loaded {} documents, {} failed, {} skipped", s.successful, s.failed, s.skipped)
  //   case Left(error) =>
  //     logger.error("Error: {}", error.message)
  // }

  // ========== 6. Incremental Sync ==========
  logger.info("6. Incremental sync operations...")
  logger.info("   Example: rag.sync(DirectoryLoader(\"./docs\"))")

  // Sync only processes changed documents:
  // val syncStats = rag.sync(DirectoryLoader("./docs"))
  // syncStats match {
  //   case Right(s) =>
  //     logger.info("Added: {}, Updated: {}, Deleted: {}, Unchanged: {}", s.added, s.updated, s.deleted, s.unchanged)
  //   case Left(error) =>
  //     logger.error("Error: {}", error.message)
  // }

  // ========== 7. Document Hints ==========
  logger.info("7. Using document hints for chunking optimization...")

  val codeDoc = Document(
    id = "code-sample",
    content = "```scala\ndef hello(): Unit = println(\"Hello\")\n```",
    hints = Some(DocumentHints.code)
  ).tap(d => logger.info("   Code document with hints: {}", d.hints.map(_.chunkingStrategy).flatten))

  val markdownDoc = Document(
    id = "markdown-sample",
    content = "# Title\n\nSome markdown content",
    hints = Some(DocumentHints.markdown)
  ).tap(d => logger.info("   Markdown document with hints: {}", d.hints.map(_.chunkingStrategy).flatten))

  // ========== 8. LoadResult Handling ==========
  logger.info("8. Handling LoadResults...")

  val results = textLoader.load().toList
  val summary = results.groupBy {
    case _: LoadResult.Success => "success"
    case _: LoadResult.Failure => "failure"
    case _: LoadResult.Skipped => "skipped"
  }

  logger.info("   Results: {}", summary.map { case (k, v) => s"$k=${v.size}" }.mkString(", "))

  // ========== 9. Version Tracking ==========
  logger.info("9. Document version tracking...")

  val docWithVersion = Document
    .create("Some content with auto-generated version")
    .tap(d => logger.info("   Document ID: {}", d.id))
    .tap(d => logger.info("   Content hash: {}", d.version.map(_.contentHash.take(16) + "...").getOrElse("none")))

  // ========== 10. Loading Configuration ==========
  logger.info("10. Loading configuration options...")

  val strictConfig   = LoadingConfig.strict          // Fail on first error
  val lenientConfig  = LoadingConfig.lenient         // Continue on errors, skip empty
  val highPerfConfig = LoadingConfig.highPerformance // More parallelism

  logger.info("   Strict: failFast={}", strictConfig.failFast)
  logger.info("   Lenient: failFast={}, skipEmpty={}", lenientConfig.failFast, lenientConfig.skipEmptyDocuments)
  logger.info("   HighPerf: parallelism={}, batchSize={}", highPerfConfig.parallelism, highPerfConfig.batchSize)

  // ========== 11. Async Operations ==========
  logger.info("11. Async/parallel operations...")
  logger.info("   RAG supports async operations for parallel document processing:")
  logger.info("   // Async ingest with parallel processing")
  logger.info("   import scala.concurrent.ExecutionContext.Implicits.global")
  logger.info("   import scala.concurrent.Await")
  logger.info("   import scala.concurrent.duration._")
  logger.info("   val futureStats = rag.ingestAsync(DirectoryLoader(\"./large-docs\"))")
  logger.info("   val stats = Await.result(futureStats, 5.minutes)")
  logger.info("   // Async sync for parallel change detection")
  logger.info("   val futureSyncStats = rag.syncAsync(DirectoryLoader(\"./docs\"))")
  logger.info("   // Async refresh")
  logger.info("   val futureRefreshStats = rag.refreshAsync(DirectoryLoader(\"./docs\"))")
  logger.info("   // Configure parallelism via LoadingConfig")
  logger.info("   val customConfig = LoadingConfig.default")
  logger.info("     .withParallelism(16)    // Up to 16 concurrent operations")
  logger.info("     .withBatchSize(50)      // Process 50 documents per batch")

  logger.info("=== Example Complete ===")
}
