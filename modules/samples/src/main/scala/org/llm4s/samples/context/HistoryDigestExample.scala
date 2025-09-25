package org.llm4s.samples.context

import org.llm4s.config.ConfigReader
import org.llm4s.context.{ ConversationTokenCounter, HistoryCompressor, SemanticBlocks }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Demonstrates HistoryCompressor.compressToDigest functionality in isolation.
 *
 * Purpose:
 *   Show how long conversations are compressed by replacing older messages with
 *   structured [HISTORY_SUMMARY] digests while preserving the most recent K messages verbatim.
 *
 * How it works:
 *   • capTokens controls the overall target budget. The digest portion is compressed
 *     until it fits within the cap; recent messages (keepLastK semantic blocks) are
 *     always kept as-is, even if this pushes the total slightly over cap.
 *   • keepLastK decides how much of the recent conversation remains untouched.
 *   • Older blocks are collapsed into one or more [HISTORY_SUMMARY] system messages
 *     that extract IDs, decisions, constraints, errors, etc.
 *
 * Expected output:
 *   • Report of original vs. final token counts for several budgets and keepLastK values.
 *   • Number of digest messages created, number of recent messages preserved.
 *   • ✅ when the digest portion fits within the budget; ⚠️ if total size exceeds due to preserved recent blocks.
 *   • A short preview of the first [HISTORY_SUMMARY].
 *
 * To run:
 * ```bash
 * sbt "samples/runMain org.llm4s.samples.context.HistoryDigestExample"
 * ```
 */

object HistoryDigestExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting History Digest Compression Example")

    val result = for {
      modelName    <-  ConfigReader.Provider().map(_.model)
      tokenCounter <- ConversationTokenCounter.forModel(modelName)
      demo         <- runHistoryDigestDemo(tokenCounter)
    } yield demo

    result.fold(
      error => logger.error(s"Demo failed: $error"),
      results => {
        logger.info("Demo completed successfully")
        displayResults(results)
      }
    )
  }

  private def runHistoryDigestDemo(
    tokenCounter: ConversationTokenCounter
  ): Result[HistoryDigestResults] = {
    logger.info("Using large realistic conversation with adversarial content for structured extraction...")

    // Add adversarial content with IDs, URLs, constraints, errors, decisions
    val adversarialMessages = createAdversarialMessages()
    val baseConversation    = ConversationFixtures.largeRealistic
    val conversation        = baseConversation.copy(messages = adversarialMessages ++ baseConversation.messages)

    val originalTokens = tokenCounter.countConversation(conversation)
    logger.info(s"Enhanced conversation: ${conversation.messages.length} messages, $originalTokens tokens")

    // Sweep both knobs - caps and keepLastK values
    val caps  = Seq(0.9, 0.6, 0.4, 0.25).map(p => (originalTokens * p).toInt)
    val keeps = Seq(0, 1, 3, 5, 10) // 0 = everything digested, large = most recent preserved

    val allResults = for {
      cap  <- caps
      keep <- keeps
    } yield testHistoryDigest(conversation, tokenCounter, cap, keep, s"cap=${cap}_keep=${keep}")

    val processedResults = allResults.collect { case Right(result) => result }

    // Test idempotence on the first result
    val idempotenceResult = processedResults.headOption.flatMap { firstResult =>
      testIdempotence(conversation, tokenCounter, firstResult.cap, firstResult.keepLastK)
    }

    val semanticBlocksResult = demonstrateSemanticBlocks(conversation.messages)

    Right(HistoryDigestResults(originalTokens, processedResults, semanticBlocksResult, idempotenceResult))
  }

  private def testHistoryDigest(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    cap: Int,
    keepLastK: Int,
    testName: String
  ): Result[DigestTestResult] = {
    logger.debug(s"Testing '$testName': cap=$cap, keepLastK=$keepLastK")

    HistoryCompressor
      .compressToDigest(
        conversation.messages,
        tokenCounter,
        cap,
        keepLastK
      )
      .map { compressedMessages =>
        val originalTokens = tokenCounter.countConversation(conversation)
        val finalTokens    = compressedMessages.map(tokenCounter.countMessage).sum
        val inspect        = inspectDigestResult(compressedMessages, tokenCounter)

        // Validate invariants
        validateInvariants(compressedMessages)

        logger.debug(oneLine(cap, keepLastK, inspect, originalTokens))

        DigestTestResult(
          testName = testName,
          cap = cap,
          keepLastK = keepLastK,
          originalTokens = originalTokens,
          finalTokens = finalTokens,
          inspect = inspect,
          success = finalTokens <= cap
        )
      }
  }

  private def demonstrateSemanticBlocks(messages: Seq[Message]): SemanticBlockDemonstration = {
    logger.info("Demonstrating semantic block grouping...")

    SemanticBlocks.groupIntoSemanticBlocks(messages) match {
      case Right(blocks) =>
        logger.info(s"Grouped ${messages.length} messages into ${blocks.length} semantic blocks")
        SemanticBlockDemonstration(
          originalMessageCount = messages.length,
          semanticBlockCount = blocks.length,
          blockSummaries = blocks.take(5).map { block => // Show first 5 blocks
            s"${block.blockType}: ${block.messages.length} msgs - ${block.getBlockSummary}"
          }
        )
      case Left(error) =>
        logger.error(s"Failed to group semantic blocks: $error")
        SemanticBlockDemonstration(messages.length, 0, Seq.empty)
    }
  }

  private def createAdversarialMessages(): Seq[Message] = Seq(
    UserMessage(
      "Here are some IDs and constraints: user-id=abc123, order-ref=xyz789, must validate payment before processing, api-key=sk-test123, url=https://api.example.com/orders"
    ),
    AssistantMessage(
      "ERROR: Payment validation failed with status code 402. I have decided to retry with exponential backoff. The system should handle timeouts gracefully and cannot process invalid requests."
    ),
    UserMessage("Check constraint: users must have verified emails and cannot exceed spending limits"),
    AssistantMessage("Processing completed. Selected the optimal retry strategy based on error patterns.")
  )

  private def inspectDigestResult(messages: Seq[Message], tokenCounter: ConversationTokenCounter): DigestInspect = {
    val (digests, recent) = messages.partition(_.content.contains("[HISTORY_SUMMARY]"))
    DigestInspect(
      totalMsgs = messages.length,
      digestMsgs = digests.length,
      recentMsgs = recent.length,
      digestOnlyTokens = digests.map(tokenCounter.countMessage).sum,
      recentOnlyTokens = recent.map(tokenCounter.countMessage).sum,
      firstDigestPreview = digests.headOption.map(_.content.take(180)).getOrElse("")
    )
  }

  private def validateInvariants(messages: Seq[Message]): Unit = {
    // All digests must start with [HISTORY_SUMMARY] and be SystemMessages
    val digestMessages = messages.filter(_.content.contains("[HISTORY_SUMMARY]"))
    require(digestMessages.forall(_.isInstanceOf[SystemMessage]), "All digest messages must be SystemMessages")
    require(
      digestMessages.forall(_.content.startsWith("[HISTORY_SUMMARY]")),
      "All digests must start with [HISTORY_SUMMARY]"
    )
  }

  private def oneLine(cap: Int, keep: Int, i: DigestInspect, total: Int): String =
    f"cap=$cap%5d keep=$keep%2d | out=${i.totalMsgs}%3d tok=${i.digestOnlyTokens + i.recentOnlyTokens}%5d (dig=${i.digestMsgs}, recent=${i.recentMsgs}) / orig=$total"

  private def testIdempotence(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    cap: Int,
    keepLastK: Int
  ): Option[IdempotenceResult] =
    HistoryCompressor.compressToDigest(conversation.messages, tokenCounter, cap, keepLastK) match {
      case Right(firstResult) =>
        HistoryCompressor.compressToDigest(firstResult, tokenCounter, cap, keepLastK) match {
          case Right(secondResult) =>
            val firstTokens   = firstResult.map(tokenCounter.countMessage).sum
            val secondTokens  = secondResult.map(tokenCounter.countMessage).sum
            val firstDigests  = firstResult.count(_.content.contains("[HISTORY_SUMMARY]"))
            val secondDigests = secondResult.count(_.content.contains("[HISTORY_SUMMARY]"))

            Some(
              IdempotenceResult(
                stable = firstTokens == secondTokens && firstDigests == secondDigests,
                firstTokens = firstTokens,
                secondTokens = secondTokens,
                firstDigests = firstDigests,
                secondDigests = secondDigests
              )
            )
          case Left(_) => None
        }
      case Left(_) => None
    }

  private def displayResults(results: HistoryDigestResults): Unit = {
    logger.info("\n📜 History Digest Compression Example Results")
    logger.info("=" * 80)

    logger.info(s"\n📊 Enhanced conversation: ${results.originalTokens} tokens")

    // Show semantic block analysis
    val blocks = results.semanticBlocks
    logger.info(s"\n🧱 Semantic Block Analysis:")
    logger.info(s"   Messages: ${blocks.originalMessageCount} → Blocks: ${blocks.semanticBlockCount}")
    logger.info("   Block samples:")
    blocks.blockSummaries.take(3).foreach(summary => logger.info(s"   • $summary"))

    // Show digest compression results in readable format
    logger.info(s"\n🔍 History Digest Compression Results:")

    // Group by cap size for easier reading
    val resultsByCap = results.digestTests.groupBy(_.cap).toSeq.sortBy(_._1)

    resultsByCap.foreach { case (cap, testsForCap) =>
      logger.info(s"\n📊 Cap: $cap tokens")
      testsForCap.sortBy(_.keepLastK).foreach { test =>
        val i         = test.inspect
        val status    = if (test.success) "✅" else "⚠️ "
        val reduction = ((test.originalTokens - test.finalTokens).toDouble / test.originalTokens * 100).toInt

        logger.info(
          s"   Keep ${test.keepLastK}: ${test.finalTokens} tokens (${reduction}% reduction) - ${i.digestMsgs} digests, ${i.recentMsgs} recent msgs $status"
        )

        // Show digest preview only for keepLastK = 0
        if (i.digestMsgs > 0 && test.keepLastK == 0) {
          val preview =
            if (i.firstDigestPreview.length > 120) i.firstDigestPreview.take(120) + "..." else i.firstDigestPreview
          logger.info(s"        Preview: $preview")
        }
      }
    }

    // Idempotence check
    results.idempotence.foreach { idem =>
      logger.info(s"\n🔄 Idempotence Check:")
      logger.info(
        s"   Stable: ${if (idem.stable) "✅" else "❌"} (${idem.firstTokens}→${idem.secondTokens} tokens, ${idem.firstDigests}→${idem.secondDigests} digests)"
      )
    }

    logger.info("\n💡 Key Observations:")
    logger.info("   • Low keepLastK values → more content gets digested")
    logger.info("   • Tight caps → more aggressive compression")
    logger.info("   • Deterministic compression (no LLM calls needed)")
    logger.info("   • Results are idempotent when run twice")
  }

  case class HistoryDigestResults(
    originalTokens: Int,
    digestTests: Seq[DigestTestResult],
    semanticBlocks: SemanticBlockDemonstration,
    idempotence: Option[IdempotenceResult]
  )

  case class DigestTestResult(
    testName: String,
    cap: Int,
    keepLastK: Int,
    originalTokens: Int,
    finalTokens: Int,
    inspect: DigestInspect,
    success: Boolean
  )

  case class DigestInspect(
    totalMsgs: Int,
    digestMsgs: Int,
    recentMsgs: Int,
    digestOnlyTokens: Int,
    recentOnlyTokens: Int,
    firstDigestPreview: String
  )

  case class IdempotenceResult(
    stable: Boolean,
    firstTokens: Int,
    secondTokens: Int,
    firstDigests: Int,
    secondDigests: Int
  )

  case class SemanticBlockDemonstration(
    originalMessageCount: Int,
    semanticBlockCount: Int,
    blockSummaries: Seq[String]
  )
}
