package org.llm4s.samples.context

import org.llm4s.config.Llm4sConfig
import org.llm4s.context.{ ContextManager, ContextConfig, ConversationTokenCounter }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, TokenBudget }
import org.slf4j.LoggerFactory

/**
 * Demonstrates the end-to-end context management pipeline using ContextManager.
 *
 * Purpose: Show how the pipeline compacts tool outputs, summarizes history into a digest,
 * optionally squeezes the digest with an LLM, and trims to fit a token budget.
 * Inputs: ConversationFixtures.largeRealistic (~9k tokens) and toolHeavy (~200k tokens).
 * Knobs (from ContextConfig): enableDeterministicCompression, enableLLMCompression,
 * summaryTokenTarget (e.g., 400), headroomPercent (e.g., 8), maxSemanticBlocks, etc.
 *
 * Expected behavior:
 *   • If conversation > budget:
 *       – ToolDeterministicCompaction runs first (externalize big tool outputs).
 *       – If still over: HistoryCompression creates [HISTORY_SUMMARY] digest(s).
 *       – If enabled and needed: LLM squeeze lowers digest to summaryTokenTarget.
 *       – FinalTokenTrim removes oldest messages (digest is pinned) to fit the window.
 *   • If conversation ≤ budget: no steps run (no-op).
 *   • Tool-heavy convo collapses mostly via ToolDeterministicCompaction alone.
 *
 * Output:
 *   • Per-scenario: original → final tokens, budget, steps applied, success flag.
 *   • Overall: success rate, average compression, step usage counts.
 *
 * To run:
 * ```bash
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-your-key-here
 * sbt "samples/runMain org.llm4s.samples.context.ContextPipelineExample"
 * ```
 */

object ContextPipelineExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Context Management Pipeline Example")

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      modelName                                  = providerCfg.model
      client       <- LLMConnect.getClient(providerCfg)
      tokenCounter <- ConversationTokenCounter.forModel(modelName)
      contextMgr   <- createContextManager(tokenCounter, client)
      results      <- runPipelineDemo(contextMgr, tokenCounter)
    } yield results

    result.fold(
      error => logger.error(s"Example failed: $error"),
      results => {
        logger.info("Example completed successfully")
        displayResults(results)
      }
    )
  }

  private def createContextManager(
    tokenCounter: ConversationTokenCounter,
    client: org.llm4s.llmconnect.LLMClient
  ): Result[ContextManager] =
    ContextManager.create(tokenCounter, ContextConfig.default, Some(client)).map { manager =>
      logger.info("Created context manager with full pipeline enabled")
      manager
    }

  private def runPipelineDemo(
    contextManager: ContextManager,
    tokenCounter: ConversationTokenCounter
  ): Result[PipelineDemoResults] = {
    val conversations = createTestConversations()
    val budgets       = Seq(5000, 10000, 20000)
    logger.info(s"Using aggressive budgets to force compression: ${budgets.mkString(", ")} tokens")

    val results = for {
      conversation <- conversations
      budget       <- budgets
      result       <- testPipelineWithScenario(contextManager, tokenCounter, conversation, budget).toOption
    } yield result

    Right(PipelineDemoResults(results))
  }

  private def testPipelineWithScenario(
    contextManager: ContextManager,
    tokenCounter: ConversationTokenCounter,
    conversation: Conversation,
    budget: TokenBudget
  ): Result[ScenarioResult] = {
    val originalTokens = tokenCounter.countConversation(conversation)

    logger.info(s"Testing pipeline: ${conversation.messages.length} messages, $originalTokens tokens → budget $budget")

    contextManager.manageContext(conversation, budget).map { managed =>
      logger.info(s"Pipeline result: ${managed.summary}")

      ScenarioResult(
        originalTokens = originalTokens,
        budget = budget,
        finalTokens = managed.finalTokens,
        stepsApplied = managed.stepsApplied.map(_.name),
        compressionRatio = managed.overallCompressionRatio,
        success = managed.finalTokens <= budget
      )
    }
  }

  private def createTestConversations(): Seq[Conversation] = Seq(
    // Scenario 1: Large realistic conversation (forces all compression steps)
    ConversationFixtures.largeRealistic,

    // Scenario 2: Tool-heavy conversation (forces tool compression first)
    ConversationFixtures.toolHeavy
  )

  private def displayResults(results: PipelineDemoResults): Unit = {
    logger.info("\n🔄 Context Management Pipeline Example Results")
    logger.info("=" * 60)

    results.scenarios.foreach { scenario =>
      logger.info(
        f"\n📊 Scenario: ${scenario.originalTokens} → ${scenario.finalTokens} tokens (budget: ${scenario.budget})"
      )
      logger.info(f"   Compression: ${(scenario.compressionRatio * 100).toInt}%% remaining")
      logger.info(f"   Steps applied: ${scenario.stepsApplied.mkString(", ")}")

      scenario.success match {
        case true  => logger.info("   ✅ Budget achieved")
        case false => logger.info("   ⚠️  Still over budget")
      }
    }

    val successRate = results.scenarios.count(_.success).toDouble / results.scenarios.length * 100
    logger.info(f"\n🎯 Overall success rate: $successRate%.1f%% scenarios fit budget")

    val avgCompression = results.scenarios.map(_.compressionRatio).sum / results.scenarios.length
    logger.info(f"📉 Average compression ratio: ${(avgCompression * 100).toInt}%%")

    val stepsUsage = results.scenarios.flatMap(_.stepsApplied).groupBy(identity).view.mapValues(_.length)
    logger.info("🔧 Pipeline steps usage:")
    stepsUsage.foreach { case (step, count) =>
      logger.info(f"   $step: $count times")
    }
  }

  case class PipelineDemoResults(scenarios: Seq[ScenarioResult])

  case class ScenarioResult(
    originalTokens: Int,
    budget: TokenBudget,
    finalTokens: Int,
    stepsApplied: Seq[String],
    compressionRatio: Double,
    success: Boolean
  )
}
