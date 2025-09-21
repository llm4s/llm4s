package org.llm4s.samples.context

import org.llm4s.context.{
  ConversationTokenCounter,
  DeterministicCompressor,
  ArtifactStore
}
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Demonstrates DeterministicCompressor functionality in isolation.
 * 
 * What it does
 * • Runs the ToolOutput pass first (schema/log/binary-aware externalization & compaction).
 * • Optionally runs subjective text rules (filler cleanup, repetition squeeze, truncate/condense).
 * • Stops when the cap is met or no further reduction is possible.
 *
 * Fixtures used
 * • largeRealistic  – mostly prose → small reduction without subjective edits; big drop when enabled.
 * • toolHeavy       – large ToolMessage payloads → huge reduction from externalization (expect 90%+).
 * • repetitive      – repetitive prose → moderate reduction with subjective edits.
 * • mixed           – small mixed content → small reduction overall.
 * • smallVerbose    – short, verbose answers → modest reduction with subjective edits.
 *
 * Interpreting results
 * • “Cap not achieved” on prose-heavy fixtures is expected with conservative caps and subjective=false.
 * • “Artifacts stored: N” appears when ToolMessage bodies are externalized (look for “[EXTERNALIZED: …]”).
 * • Subjective pass may run multiple internal rounds but is bounded and deterministic.
 *
 * To run this example:
 * ```bash
 * sbt "samples/runMain org.llm4s.samples.context.DeterministicCompressionExample"
 * ```
 */
object DeterministicCompressionExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Deterministic Compression Example")

    val result = for {
      tokenCounter <- createTokenCounter()
      results      <- runCompressionTests(tokenCounter)
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

  private def runCompressionTests(tokenCounter: ConversationTokenCounter): Result[CompressionResults] = {
    val testFixtures = getTestFixtures()
    val subjectiveOptions = Seq(false, true)
    
    val results = for {
      (fixtureName, conversation) <- testFixtures
      enableSubjective <- subjectiveOptions
      testName = s"$fixtureName (subjective=$enableSubjective)"
      result <- testDeterministicCompression(testName, conversation, tokenCounter, enableSubjective).toOption
    } yield result

    Right(CompressionResults(results))
  }

  private def getTestFixtures(): Seq[(String, Conversation)] = Seq(
    "largeRealistic" -> ConversationFixtures.largeRealistic,
    "toolHeavy" -> ConversationFixtures.toolHeavy,
    "repetitive" -> ConversationFixtures.repetitive,
    "mixed" -> ConversationFixtures.mixed,
    "smallVerbose" -> ConversationFixtures.smallVerbose
  )

  private def testDeterministicCompression(
    testName: String,
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    enableSubjectiveEdits: Boolean
  ): Result[CompressionResult] = {
    val originalTokens = tokenCounter.countConversation(conversation)
    val targetCap = (originalTokens * 0.6).toInt // Aim for 60% of original tokens
    val artifactStore = ArtifactStore.inMemory()
    
    logger.info(s"Testing '$testName': $originalTokens tokens → target $targetCap tokens")

    DeterministicCompressor.compressToCap(
      conversation.messages,
      tokenCounter,
      targetCap,
      Some(artifactStore),
      enableSubjectiveEdits = enableSubjectiveEdits
    ).map { compressedMessages =>
      val finalTokens = compressedMessages.map(tokenCounter.countMessage).sum
      val compressionRatio = finalTokens.toDouble / originalTokens
      val reductionPercent = ((1.0 - compressionRatio) * 100).toInt
      val capAchieved = finalTokens <= targetCap

      logger.info(s"'$testName': $originalTokens → $finalTokens tokens ($reductionPercent% reduction), cap achieved: $capAchieved")

      // Determine which passes were applied based on the configuration and results
      val toolPassApplied = compressedMessages.exists(_.content.contains("EXTERNALIZED"))
      val threshold = toolPassApplied match {
        case true  => originalTokens - 100
        case false => originalTokens
      }
      val subjectivePassApplied = enableSubjectiveEdits && (finalTokens < threshold)
      
      // Count artifacts by checking for externalized messages
      val artifactCount = compressedMessages.count(_.content.contains("EXTERNALIZED"))

      CompressionResult(
        testName = testName,
        originalTokens = originalTokens,
        finalTokens = finalTokens,
        reductionPercent = reductionPercent,
        capAchieved = capAchieved,
        toolPassApplied = toolPassApplied,
        subjectivePassApplied = subjectivePassApplied,
        artifactCount = artifactCount
      )
    }
  }

  private def displayResults(results: CompressionResults): Unit = {
    logger.info("\n🗜️ Deterministic Compression Example Results")
    logger.info("=" * 60)

    results.results.foreach { result =>
      val passesInfo = Seq(
        result.toolPassApplied match {
          case true  => "tool_pass=true"
          case false => "tool_pass=false"
        },
        result.subjectivePassApplied match {
          case true  => "subjective_pass=true"
          case false => "subjective_pass=false"
        }
      ).mkString(", ")

      val capStatus = result.capAchieved match {
        case true  => "✅ Cap achieved"
        case false => "⚠️ Cap not achieved"
      }
      
      logger.info(f"\n📊 ${result.testName}")
      logger.info(f"   Compression: ${result.originalTokens} → ${result.finalTokens} tokens (${result.reductionPercent}%% reduction)")
      logger.info(f"   $capStatus")
      logger.info(f"   Passes: $passesInfo")
      
      result.artifactCount match {
        case count if count > 0 => logger.info(f"   Artifacts stored: $count")
        case _                  => // No artifacts to report
      }
    }

    // Summary statistics
    val withoutSubjective = results.results.filter(_.testName.contains("subjective=false"))
    val withSubjective = results.results.filter(_.testName.contains("subjective=true"))

    if (withoutSubjective.nonEmpty) {
      val avgReduction = withoutSubjective.map(_.reductionPercent).sum.toDouble / withoutSubjective.length
      val successRate = withoutSubjective.count(_.capAchieved).toDouble / withoutSubjective.length * 100
      logger.info(f"\n📈 Without subjective edits: ${avgReduction}%.1f%% avg reduction, ${successRate}%.1f%% cap success rate")
    }

    if (withSubjective.nonEmpty) {
      val avgReduction = withSubjective.map(_.reductionPercent).sum.toDouble / withSubjective.length
      val successRate = withSubjective.count(_.capAchieved).toDouble / withSubjective.length * 100
      logger.info(f"📈 With subjective edits: ${avgReduction}%.1f%% avg reduction, ${successRate}%.1f%% cap success rate")
    }

    logger.info("\n💡 Key Observations:")
    logger.info("   • Tool output compression always runs first (when applicable)")
    logger.info("   • Subjective compression rules provide additional reduction")
    logger.info("   • Deterministic = no LLM calls, fast and predictable")
    logger.info("   • Success depends on content type and target compression ratio")
  }

  case class CompressionResults(results: Seq[CompressionResult])

  case class CompressionResult(
    testName: String,
    originalTokens: Int,
    finalTokens: Int,
    reductionPercent: Int,
    capAchieved: Boolean,
    toolPassApplied: Boolean,
    subjectivePassApplied: Boolean,
    artifactCount: Int
  )
}