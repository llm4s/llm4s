package org.llm4s.samples.context

import org.llm4s.context.{
  ConversationTokenCounter,
  ToolOutputCompressor,
  ArtifactStore
}
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, ContentSize }
import org.slf4j.LoggerFactory
import ujson._

/**
 * Demonstrates ToolOutputCompressor functionality in isolation.
 *
 * Purpose: Show how large tool outputs (JSON, logs, stack traces, binary, DB results)
 * are either compressed inline or externalized into an ArtifactStore with pointers.
 * Inputs: Synthetic ToolMessages (large JSON, long logs, deep stacktrace, base64 image,
 * large DB query result).
 * Knobs: threshold in bytes (e.g., 2KB vs 8KB), using ArtifactStore.inMemory().
 *
 * Expected behavior:
 *   • If output size > threshold → externalized with [EXTERNALIZED: key | KIND | preview].
 *   • If compressed size < threshold → remains inline (smaller representation).
 *   • Binary/base64 content → always externalized, regardless of threshold.
 *   • Lower thresholds → more externalization; higher thresholds → more inline results.
 *
 * Output:
 *   • Original vs compressed size in bytes, % reduction
 *   • Whether content was [EXTERNALIZED] or [INLINE]
 *   • Count of artifacts stored in the ArtifactStore
 *   • Overall reduction summary per threshold
 *
 * To run this example:
 * ```bash
 * sbt "samples/runMain org.llm4s.samples.context.ToolExternalizationExample"
 * ```
 */
object ToolExternalizationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Tool Output Externalization Example")

    val result = for {
      tokenCounter <- createTokenCounter()
      results      <- runExternalizationTests(tokenCounter)
    } yield results

    result.fold(
      error => logger.error(s"Example failed: $error"),
      results => {
        logger.info("Example completed successfully")
        displayResults(results)
      }
    )
  }

  private def createTokenCounter(): Result[ConversationTokenCounter] =
    ConversationTokenCounter.openAI()

  private def runExternalizationTests(tokenCounter: ConversationTokenCounter): Result[ExternalizationResults] = {
    val toolMessages = createSyntheticToolMessages()
    val thresholds = Seq(2048, 8192) // 2KB and 8KB thresholds
    
    val allResults = for {
      threshold <- thresholds
    } yield {
      logger.info(s"Testing with threshold: ${threshold} bytes")
      val artifactStore = ArtifactStore.inMemory()
      
      val results = toolMessages.map { case (description, message) =>
        testToolMessage(description, message, threshold, tokenCounter, artifactStore)
      }
      
      val successfulResults = results.collect { case Right(result) => result }
      val artifactCount = successfulResults.count(_.wasExternalized)
      ThresholdTestResult(threshold, successfulResults, artifactCount)
    }
    
    Right(ExternalizationResults(allResults))
  }

  private def createSyntheticToolMessages(): Seq[(String, ToolMessage)] = Seq(
    "Large JSON API Response" -> createLargeJsonOutput(),
    "System Logs" -> createLogOutput(),
    "Error Stack Trace" -> createErrorOutput(),
    "Binary Image Data" -> createBinaryOutput(),
    "Database Query Result" -> createDatabaseOutput()
  )

  private def createLargeJsonOutput(): ToolMessage = {
    val largeJson = Obj(
      "users" -> Arr.from((1 to 500).map { i =>
        Obj(
          "id" -> Num(i),
          "name" -> Str(s"User $i"),
          "email" -> Str(s"user$i@example.com"),
          "profile" -> Obj(
            "age" -> Num(25 + i % 50),
            "location" -> Str("New York"),
            "preferences" -> Arr(Str("coding"), Str("reading"), Str("music")),
            "metadata" -> Null,
            "empty_field" -> Str(""),
            "settings" -> Obj(
              "theme" -> Str("dark"),
              "notifications" -> Bool(true),
              "privacy" -> Str("public")
            )
          )
        )
      }),
      "pagination" -> Obj(
        "total" -> Num(500),
        "page" -> Num(1),
        "per_page" -> Num(100),
        "has_next" -> Bool(true)
      ),
      "timestamp" -> Str("2024-01-01T00:00:00Z")
    )

    ToolMessage(write(largeJson, indent = 2), "fetch_users_api")
  }

  private def createLogOutput(): ToolMessage = {
    val logLines = (1 to 300).map { i =>
      val level = Seq("INFO", "DEBUG", "WARN", "ERROR")(i % 4)
      val timestamp = f"2024-01-01 12:${(i / 60) % 24}%02d:${i % 60}%02d"
      val component = Seq("UserService", "DatabasePool", "AuthManager", "CacheLayer")(i % 4)
      s"$timestamp $level [$component] Processing request #$i with detailed context and metadata"
    }.mkString("\n")

    ToolMessage(logLines, "system_monitoring")
  }

  private def createErrorOutput(): ToolMessage = {
    val errorContent = """ERROR: Database connection failed
java.sql.SQLException: Connection to database 'production' failed after 30 seconds
	at com.example.db.ConnectionPool.getConnection(ConnectionPool.java:156)
	at com.example.service.UserService.findById(UserService.java:89)
	at com.example.controller.UserController.getUser(UserController.java:45)
	at com.example.web.RouteHandler.handleRequest(RouteHandler.java:234)
	at com.example.server.HttpServer.processRequest(HttpServer.java:178)
	at com.example.server.RequestProcessor.run(RequestProcessor.java:92)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	at java.base/java.lang.Thread.run(Thread.java:833)""" +
    (1 to 40).map(i => s"\n	at com.example.deep.nested.Frame$i.method$i(Frame$i.java:${100 + i})").mkString

    ToolMessage(errorContent, "error_diagnostics")
  }

  private def createBinaryOutput(): ToolMessage = {
    // Simulate a large base64-encoded image
    val base64Header = "data:image/png;base64,"
    val base64Data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==" * 200
    
    ToolMessage(base64Header + base64Data, "image_generation")
  }

  private def createDatabaseOutput(): ToolMessage = {
    val queryResult = (1 to 1000).map { i =>
      s"$i|Product $i|${19.99 + (i % 100)}|In Stock|Category ${i % 10}|2024-01-${(i % 28) + 1}"
    }.mkString("\n")
    
    ToolMessage(s"Query executed successfully. Results:\nid|name|price|status|category|created\n$queryResult", "database_query")
  }


  private def testToolMessage(
    description: String,
    toolMessage: ToolMessage,
    threshold: Long,
    tokenCounter: ConversationTokenCounter,
    artifactStore: ArtifactStore
  ): Result[MessageTestResult] = {
    val originalSize = ContentSize.fromString(toolMessage.content).bytes
    val originalTokens = tokenCounter.countMessage(toolMessage)
    
    logger.debug(s"Testing '$description': $originalSize bytes, $originalTokens tokens")

    ToolOutputCompressor.compressToolOutputs(Seq(toolMessage), artifactStore, threshold).map { compressed =>
      val processedMessage = compressed.head
      val compressedSize = ContentSize.fromString(processedMessage.content).bytes
      val compressedTokens = tokenCounter.countMessage(processedMessage)
      val wasExternalized = processedMessage.content.contains("EXTERNALIZED")
      
      val sizeSavings = ((originalSize - compressedSize).toDouble / originalSize * 100).toInt
      val tokenSavings = ((originalTokens - compressedTokens).toDouble / originalTokens * 100).toInt

      logger.debug(s"'$description': ${if (wasExternalized) "EXTERNALIZED" else "INLINE"} - $sizeSavings% size reduction")

      MessageTestResult(
        description = description,
        originalSizeBytes = originalSize,
        compressedSizeBytes = compressedSize,
        originalTokens = originalTokens,
        compressedTokens = compressedTokens,
        wasExternalized = wasExternalized,
        externalizedPointer = if (wasExternalized) Some(processedMessage.content) else None,
        sizeSavingsPercent = sizeSavings,
        tokenSavingsPercent = tokenSavings
      )
    }
  }

  private def displayResults(results: ExternalizationResults): Unit = {
    logger.info("\n📦 Tool Output Externalization Example Results")
    logger.info("=" * 70)

    results.thresholdResults.foreach { thresholdResult =>
      logger.info(f"\n🔩 Threshold: ${thresholdResult.threshold} bytes")
      logger.info(f"   Artifacts stored: ${thresholdResult.artifactCount}")
      logger.info("   Per-message results:")
      
      thresholdResult.messageResults.foreach { msgResult =>
        val status = if (msgResult.wasExternalized) "[EXTERNALIZED]" else "[INLINE]"
        logger.info(f"   • ${msgResult.description}: ${msgResult.originalSizeBytes} → ${msgResult.compressedSizeBytes} bytes (${msgResult.sizeSavingsPercent}%% saved) $status")
        
        msgResult.externalizedPointer.foreach { pointer =>
          val preview = if (pointer.length > 100) pointer.take(100) + "..." else pointer
          logger.info(f"     Pointer: $preview")
        }
      }
      
      val totalOriginal = thresholdResult.messageResults.map(_.originalSizeBytes).sum
      val totalCompressed = thresholdResult.messageResults.map(_.compressedSizeBytes).sum
      val externalizedCount = thresholdResult.messageResults.count(_.wasExternalized)
      val overallReduction = ((totalOriginal - totalCompressed).toDouble / totalOriginal * 100).toInt
      
      logger.info(f"   Summary: $overallReduction%% overall reduction, $externalizedCount/${thresholdResult.messageResults.length} externalized")
    }
    
    logger.info("\n💡 Key Observations:")
    logger.info("   • Lower thresholds → more externalization")
    logger.info("   • Binary data always externalized regardless of threshold")
    logger.info("   • JSON/logs get inline compression before externalization")
    logger.info("   • Each externalized item gets [EXTERNALIZED: key] pointer")
    logger.info("   • Artifact store uses content-addressing for deduplication")
  }

  case class ExternalizationResults(thresholdResults: Seq[ThresholdTestResult])

  case class ThresholdTestResult(
    threshold: Long,
    messageResults: Seq[MessageTestResult],
    artifactCount: Int
  )

  case class MessageTestResult(
    description: String,
    originalSizeBytes: Long,
    compressedSizeBytes: Long,
    originalTokens: Int,
    compressedTokens: Int,
    wasExternalized: Boolean,
    externalizedPointer: Option[String],
    sizeSavingsPercent: Int,
    tokenSavingsPercent: Int
  )
}