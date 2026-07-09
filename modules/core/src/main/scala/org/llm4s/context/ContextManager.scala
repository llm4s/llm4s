package org.llm4s.context

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Message }
import org.llm4s.types.{ Result, TokenBudget, HeadroomPercent, ContextWindowSize }
import org.slf4j.LoggerFactory

/**
 * Orchestrates the 4-step context management pipeline for llm4s conversations.
 *
 * `ContextManager` is the primary entry point for keeping a conversation within a
 * model's token limit. Each step is applied in order of increasing cost; the pipeline
 * exits early as soon as the conversation fits the requested budget.
 *
 * ==Compressor Comparison==
 *
 * {{{
 * Strategy                | Cost        | Quality | Latency | What it touches
 * ------------------------|-------------|---------|---------|-------------------------
 * DeterministicCompressor | Free        | Lower   | Fast    | Tool outputs only
 * HistoryCompressor       | Free        | Medium  | Fast    | Older history → digest
 * LLMCompressor           | 1 LLM call  | High    | Slow    | Digest messages only
 * }}}
 *
 * ==4-Step Pipeline==
 *
 * Each step exits immediately if the budget is already met:
 *
 *  1. '''ToolDeterministicCompaction''' ([[DeterministicCompressor]]):
 *     Shrinks and caps tool outputs (JSON, logs, binary content) without modifying
 *     user or assistant messages. No API calls; always runs first.
 *
 *  2. '''HistoryCompression''' ([[HistoryCompressor]]):
 *     Keeps the last `config.maxSemanticBlocks` semantic blocks verbatim and
 *     replaces older blocks with compact `[HISTORY_SUMMARY]` digests, capped to
 *     `config.summaryTokenTarget` tokens. No API calls.
 *
 *  3. '''LLMHistorySqueeze''' ([[LLMCompressor]]):
 *     If still over budget and `config.enableLLMCompression` is `true`, compresses
 *     only the digest messages further via one LLM inference call per digest.
 *
 *  4. '''FinalTokenTrim''' ([[TokenWindow]]):
 *     Hard-trims to `budget` tokens (with `config.headroomPercent`), always pinning
 *     `[HISTORY_SUMMARY]` messages so they are never dropped.
 *
 * ==Usage==
 *
 * {{{
 * // Quick setup with defaults:
 * val manager = ContextManager.withDefaults(tokenCounter).getOrElse(???)
 * val result  = manager.manageContext(conversation, budget = 8000)
 * result.foreach(managed => println(managed.summary))
 *
 * // With an LLM client for Step 3:
 * val manager = ContextManager.create(tokenCounter, ContextConfig.default, Some(llmClient))
 *   .getOrElse(???)
 * }}}
 *
 * @param tokenCounter  Token counter calibrated to the target model's tokenizer
 * @param config        Pipeline configuration — controls headroom, semantic block count,
 *                      and which steps are enabled
 * @param llmClient     Optional LLM client; required for Step 3 (LLMHistorySqueeze);
 *                      Step 3 is skipped if `None`
 * @param artifactStore Optional store for externalized binary/large content from Step 1;
 *                      defaults to an in-memory store if `None`
 *
 * @see [[DeterministicCompressor]] for Step 1 implementation
 * @see [[HistoryCompressor]] for Step 2 implementation
 * @see [[LLMCompressor]] for Step 3 implementation
 * @see [[TokenWindow]] for Step 4 implementation
 * @see [[ContextConfig]] for all configuration options
 */
class ContextManager(
  tokenCounter: ConversationTokenCounter,
  config: ContextConfig,
  llmClient: Option[LLMClient] = None,
  artifactStore: Option[ArtifactStore] = None
) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Apply 4-step context management pipeline to fit conversation within budget
   */
  def manageContext(conversation: Conversation, budget: TokenBudget): Result[ManagedConversation] = {
    logger.info(
      s"Starting new context management pipeline for ${conversation.messages.length} messages, budget: $budget tokens"
    )

    for {
      step1 <- applyToolDeterministicCompaction(conversation.messages)
      step2 <- applyHistoryCompression(step1.messages, budget)
      step3 <- applyLLMHistorySqueeze(step2.messages, budget)
      step4 <- applyFinalTokenTrim(step3.messages, budget)
    } yield ManagedConversation(
      conversation = Conversation(step4.messages),
      originalTokens = tokenCounter.countConversation(conversation),
      finalTokens = tokenCounter.countConversation(Conversation(step4.messages)),
      steps = Seq(step1.toContextStep, step2.toContextStep, step3.toContextStep, step4.toContextStep)
    )
  }

  /**
   * Step 1: Tool deterministic compaction (tool outputs first)
   */
  private def applyToolDeterministicCompaction(messages: Seq[Message]): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))

    if (!config.enableDeterministicCompression) {
      logger.debug("Deterministic compression disabled in configuration")
      Right(PipelineStep("ToolDeterministicCompaction", messages, currentTokens, currentTokens, applied = false))
    } else {
      // Cap equals current size: allows compaction but never forces arbitrary reduction.
      val capAtCurrent = currentTokens
      logger.info("Applying tool deterministic compaction")

      DeterministicCompressor
        .compressToCap(
          messages,
          tokenCounter,
          capAtCurrent,
          artifactStore,
          enableSubjectiveEdits = false // Tool compaction only in Step 1
        )
        .map { compressedMessages =>
          val finalTokens = tokenCounter.countConversation(Conversation(compressedMessages))
          PipelineStep(
            name = "ToolDeterministicCompaction",
            messages = compressedMessages,
            tokensBefore = currentTokens,
            tokensAfter = finalTokens,
            applied = compressedMessages != messages
          )
        }
    }
  }

  /**
   * Step 2: History compression (structured digest)
   */
  private def applyHistoryCompression(messages: Seq[Message], budget: TokenBudget): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))

    if (currentTokens <= budget) {
      logger.debug("Messages fit budget, skipping history compression")
      Right(PipelineStep("HistoryCompression", messages, currentTokens, currentTokens, applied = false))
    } else {
      logger.info("Applying history compression with deterministic digest")
      HistoryCompressor
        .compressToDigest(messages, tokenCounter, config.summaryTokenTarget, config.maxSemanticBlocks)
        .map { compressedMessages =>
          val finalTokens = tokenCounter.countConversation(Conversation(compressedMessages))
          PipelineStep(
            name = "HistoryCompression",
            messages = compressedMessages,
            tokensBefore = currentTokens,
            tokensAfter = finalTokens,
            applied = compressedMessages != messages
          )
        }
    }
  }

  /**
   * Step 3: LLM history squeeze (digest-only compression)
   */
  private def applyLLMHistorySqueeze(messages: Seq[Message], budget: TokenBudget): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))
    val skipStep      = PipelineStep("LLMHistorySqueeze", messages, currentTokens, currentTokens, applied = false)

    if (currentTokens <= budget) {
      logger.debug("Messages fit budget, skipping LLM history squeeze")
      Right(skipStep)
    } else if (!config.enableLLMCompression || llmClient.isEmpty) {
      logger.debug("LLM compression disabled or no client available")
      Right(skipStep)
    } else {
      logger.info("Applying LLM history squeeze to digest messages")
      LLMCompressor.squeezeDigest(messages, tokenCounter, llmClient.get, config.summaryTokenTarget).map {
        compressedMessages =>
          val finalTokens = tokenCounter.countConversation(Conversation(compressedMessages))
          PipelineStep(
            name = "LLMHistorySqueeze",
            messages = compressedMessages,
            tokensBefore = currentTokens,
            tokensAfter = finalTokens,
            applied = compressedMessages != messages
          )
      }
    }
  }

  /**
   * Step 4: Final token trim (with digest pinned)
   */
  private def applyFinalTokenTrim(messages: Seq[Message], budget: TokenBudget): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))
    val conversation  = Conversation(messages)

    TokenWindow.trimToBudget(conversation, tokenCounter, budget, config.headroomPercent.asRatio).map { window =>
      PipelineStep(
        name = "FinalTokenTrim",
        messages = window.conversation.messages,
        tokensBefore = currentTokens,
        tokensAfter = window.usage.currentTokens,
        applied = window.wasTrimmed
      )
    }
  }

}

/**
 * Represents a single step in the new 4-stage context management pipeline
 */
case class PipelineStep(
  name: String,
  messages: Seq[Message],
  tokensBefore: Int,
  tokensAfter: Int,
  applied: Boolean
) {
  def toContextStep: ContextStep = ContextStep(
    name = name,
    conversation = Conversation(messages),
    tokensBefore = tokensBefore,
    tokensAfter = tokensAfter,
    applied = applied
  )
}

object ContextManager {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create context manager with configuration and LLM client
   */
  def create(
    tokenCounter: ConversationTokenCounter,
    config: ContextConfig,
    llmClient: Option[LLMClient] = None,
    artifactStore: Option[ArtifactStore] = None
  ): Result[ContextManager] =
    validateConfig(config).map { _ =>
      logger.debug(s"Created context manager with config: $config")
      new ContextManager(tokenCounter, config, llmClient, artifactStore)
    }

  /**
   * Create context manager with default configuration
   */
  def withDefaults(
    tokenCounter: ConversationTokenCounter,
    llmClient: Option[LLMClient] = None,
    artifactStore: Option[ArtifactStore] = None
  ): Result[ContextManager] =
    create(tokenCounter, ContextConfig.default, llmClient, artifactStore)

  private def validateConfig(config: ContextConfig): Result[Unit] =
    if (config.headroomPercent.isValid) Right(())
    else
      Left(
        org.llm4s.error.ValidationError(
          s"Invalid headroom percent: ${config.headroomPercent}. Must be between 0.0 and 1.0",
          "headroomPercent"
        )
      )
}

/**
 * Configuration for context management pipeline
 */
case class ContextConfig(
  headroomPercent: HeadroomPercent,
  maxSemanticBlocks: ContextWindowSize,
  enableRollingSummary: Boolean, // @deprecated - kept for backward compatibility
  enableDeterministicCompression: Boolean,
  enableLLMCompression: Boolean,
  summaryTokenTarget: Int = 400,         // New: target tokens for digest summaries
  enableSubjectiveEdits: Boolean = false // New: gates filler/repetition/truncate rules
)

object ContextConfig {
  val default: ContextConfig = ContextConfig(
    headroomPercent = HeadroomPercent.Standard, // 8% headroom
    maxSemanticBlocks = 5,                      // Keep last 5 semantic blocks for history compression
    enableRollingSummary = false,               // Deprecated in favor of history compression
    enableDeterministicCompression = true,
    enableLLMCompression = true,
    summaryTokenTarget = 400,
    enableSubjectiveEdits = false // Conservative default - no subjective text edits
  )

  /**
   * Create config with backward compatibility for existing usage
   */
  def legacy(
    headroomPercent: HeadroomPercent = HeadroomPercent.Standard,
    maxSemanticBlocks: ContextWindowSize = 5,
    enableRollingSummary: Boolean = true,
    enableDeterministicCompression: Boolean = true,
    enableLLMCompression: Boolean = true
  ): ContextConfig = ContextConfig(
    headroomPercent = headroomPercent,
    maxSemanticBlocks = maxSemanticBlocks,
    enableRollingSummary = enableRollingSummary,
    enableDeterministicCompression = enableDeterministicCompression,
    enableLLMCompression = enableLLMCompression,
    summaryTokenTarget = 400,
    enableSubjectiveEdits = false
  )
}

/**
 * Represents a single step in the context management pipeline
 */
case class ContextStep(
  name: String,
  conversation: Conversation,
  tokensBefore: Int,
  tokensAfter: Int,
  applied: Boolean
) {
  def summary: String =
    if (applied) f"$name: $tokensBefore → $tokensAfter tokens (${tokensSaved} saved)"
    else f"$name: skipped ($tokensBefore tokens)"

  def tokensSaved: Int         = tokensBefore - tokensAfter
  def compressionRatio: Double = tokensAfter.toDouble / tokensBefore
}

object ContextStep {
  def noOperation(name: String, conversation: Conversation, tokens: Int): ContextStep =
    ContextStep(name, conversation, tokens, tokens, applied = false)
}

/**
 * Final result of context management pipeline
 */
case class ManagedConversation(
  conversation: Conversation,
  originalTokens: Int,
  finalTokens: Int,
  steps: Seq[ContextStep]
) {
  def totalTokensSaved: Int           = originalTokens - finalTokens
  def overallCompressionRatio: Double = finalTokens.toDouble / originalTokens
  def stepsApplied: Seq[ContextStep]  = steps.filter(_.applied)

  def summary: String =
    f"Context management: $originalTokens → $finalTokens tokens " +
      f"(${(overallCompressionRatio * 100).toInt}%% remaining, ${stepsApplied.length} steps applied)"
}
