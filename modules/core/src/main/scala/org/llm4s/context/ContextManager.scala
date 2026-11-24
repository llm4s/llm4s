package org.llm4s.context

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Message }
import org.llm4s.error.ValidationError
import org.llm4s.types.{ HeadroomPercent, Result }
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
 *     `config.summaryTokenTarget` tokens, and to at most half the budget left after headroom, so that the
 *     digests, which step 4 pins, leave room for the recent turns. No API calls.
 *
 *  3. '''LLMHistorySqueeze''' ([[LLMCompressor]]):
 *     If still over budget and `config.enableLLMCompression` is `true`, compresses
 *     only the digest messages further via one LLM inference call per digest.
 *
 *  4. '''FinalTokenTrim''' ([[TokenWindow]]):
 *     Hard-trims to `budget` tokens (with `config.headroomPercent`), pinning the leading system and
 *     `[HISTORY_SUMMARY]` messages and dropping older turns whole, a tool call with its results. Fails
 *     when not even the newest turn fits beside the pinned messages.
 *
 * ==Usage==
 *
 * {{{
 * // Quick setup with defaults:
 * val manager = ContextManager.create(tokenCounter).getOrElse(???)
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
 * @param artifactStore Store for externalized binary/large content from Step 1. With `None` nothing is
 *                      externalized: large tool output is compressed inline and binary content is kept
 *
 * @see [[DeterministicCompressor]] for Step 1 implementation
 * @see [[HistoryCompressor]] for Step 2 implementation
 * @see [[LLMCompressor]] for Step 3 implementation
 * @see [[TokenWindow]] for Step 4 implementation
 * @see [[ContextConfig]] for all configuration options
 */
@Experimental
final class ContextManager private (
  tokenCounter: ConversationTokenCounter,
  config: ContextConfig,
  llmClient: Option[LLMClient],
  artifactStore: Option[ArtifactStore]
) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Apply 4-step context management pipeline to fit conversation within budget
   */
  def manageContext(conversation: Conversation, budget: Int): Result[ManagedConversation] = {
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
      Right(
        PipelineStep(ContextStage.ToolDeterministicCompaction, messages, currentTokens, currentTokens, applied = false)
      )
    } else {
      // Cap equals current size: allows compaction but never forces arbitrary reduction.
      val capAtCurrent = currentTokens
      logger.info("Applying tool deterministic compaction")

      DeterministicCompressor
        .compressToCap(
          messages,
          tokenCounter,
          capAtCurrent,
          artifactStore
        )
        .map { compressedMessages =>
          val finalTokens = tokenCounter.countConversation(Conversation(compressedMessages))
          PipelineStep(
            stage = ContextStage.ToolDeterministicCompaction,
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
  private def applyHistoryCompression(messages: Seq[Message], budget: Int): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))

    if (currentTokens <= budget) {
      logger.debug("Messages fit budget, skipping history compression")
      Right(PipelineStep(ContextStage.HistoryCompression, messages, currentTokens, currentTokens, applied = false))
    } else {
      logger.info("Applying history compression with deterministic digest")
      // Step 4 pins every digest, so they may take at most half the usable budget.
      val usable    = (budget * (1.0 - config.headroomPercent.asRatio)).toInt
      val digestCap = math.max(1, math.min(config.summaryTokenTarget, usable / 2))
      HistoryCompressor
        .compressToDigest(messages, tokenCounter, digestCap, config.maxSemanticBlocks)
        .map { compressedMessages =>
          val finalTokens = tokenCounter.countConversation(Conversation(compressedMessages))
          PipelineStep(
            stage = ContextStage.HistoryCompression,
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
  private def applyLLMHistorySqueeze(messages: Seq[Message], budget: Int): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))
    val skipStep = PipelineStep(ContextStage.LLMHistorySqueeze, messages, currentTokens, currentTokens, applied = false)

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
            stage = ContextStage.LLMHistorySqueeze,
            messages = compressedMessages,
            tokensBefore = currentTokens,
            tokensAfter = finalTokens,
            applied = compressedMessages != messages
          )
      }
    }
  }

  /**
   * Step 4: Final token trim (leading system and digest messages pinned)
   */
  private def applyFinalTokenTrim(messages: Seq[Message], budget: Int): Result[PipelineStep] = {
    val currentTokens = tokenCounter.countConversation(Conversation(messages))
    val conversation  = Conversation(messages)

    TokenWindow.trimToBudget(conversation, tokenCounter, budget, config.headroomPercent).map { window =>
      PipelineStep(
        stage = ContextStage.FinalTokenTrim,
        messages = window.conversation.messages,
        tokensBefore = currentTokens,
        tokensAfter = window.usage.currentTokens,
        applied = window.wasTrimmed
      )
    }
  }

}

/** One stage's outcome inside [[ContextManager]], before it is reported as a [[ContextStep]]. */
final private case class PipelineStep(
  stage: ContextStage,
  messages: Seq[Message],
  tokensBefore: Int,
  tokensAfter: Int,
  applied: Boolean
) {
  def toContextStep: ContextStep = ContextStep(
    stage = stage,
    conversation = Conversation(messages),
    tokensBefore = tokensBefore,
    tokensAfter = tokensAfter,
    applied = applied
  )
}

/**
 * The stages of the [[ContextManager]] pipeline, in the order they run.
 */
@Experimental
enum ContextStage {

  /** Tool outputs are compacted and capped ([[DeterministicCompressor]]). */
  case ToolDeterministicCompaction

  /** Older history is replaced by digests ([[HistoryCompressor]]). */
  case HistoryCompression

  /** The digests are compressed further by a model ([[LLMCompressor]]). */
  case LLMHistorySqueeze

  /** Older turns are dropped until the conversation fits ([[TokenWindow]]). */
  case FinalTokenTrim
}

@Experimental
object ContextManager {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Creates a context manager.
   *
   * @param tokenCounter  Token counter calibrated to the target model's tokenizer
   * @param config        Pipeline configuration; [[ContextConfig.default]] when omitted
   * @param llmClient     Client for the LLM history squeeze; the stage is skipped without one
   * @param artifactStore Store for externalized tool output; nothing is externalized without one
   * @return the manager, or a `ValidationError` when `config.maxSemanticBlocks` is negative or
   *         `config.summaryTokenTarget` is not positive
   */
  def create(
    tokenCounter: ConversationTokenCounter,
    config: ContextConfig = ContextConfig.default,
    llmClient: Option[LLMClient] = None,
    artifactStore: Option[ArtifactStore] = None
  ): Result[ContextManager] =
    validateConfig(config).map { _ =>
      logger.debug(s"Created context manager with config: $config")
      new ContextManager(tokenCounter, config, llmClient, artifactStore)
    }

  private def validateConfig(config: ContextConfig): Result[Unit] =
    if (config.maxSemanticBlocks < 0)
      Left(ValidationError(s"maxSemanticBlocks must not be negative: ${config.maxSemanticBlocks}", "maxSemanticBlocks"))
    else if (config.summaryTokenTarget <= 0)
      Left(ValidationError(s"summaryTokenTarget must be positive: ${config.summaryTokenTarget}", "summaryTokenTarget"))
    else Right(())
}

/**
 * Configuration for context management pipeline
 */
@Experimental
final case class ContextConfig private (
  headroomPercent: HeadroomPercent,
  maxSemanticBlocks: Int,
  enableDeterministicCompression: Boolean,
  enableLLMCompression: Boolean,
  summaryTokenTarget: Int // target tokens for digest summaries
) {
  def withHeadroomPercent(headroomPercent: HeadroomPercent): ContextConfig = copy(headroomPercent = headroomPercent)
  def withMaxSemanticBlocks(maxSemanticBlocks: Int): ContextConfig =
    copy(maxSemanticBlocks = maxSemanticBlocks)
  def withEnableDeterministicCompression(enableDeterministicCompression: Boolean): ContextConfig =
    copy(enableDeterministicCompression = enableDeterministicCompression)
  def withEnableLLMCompression(enableLLMCompression: Boolean): ContextConfig =
    copy(enableLLMCompression = enableLLMCompression)
  def withSummaryTokenTarget(summaryTokenTarget: Int): ContextConfig = copy(summaryTokenTarget = summaryTokenTarget)
}

@Experimental
object ContextConfig {

  /** Creates a [[ContextConfig]]. Named arguments are the supported way to construct one. */
  def apply(
    headroomPercent: HeadroomPercent,
    maxSemanticBlocks: Int,
    enableDeterministicCompression: Boolean,
    enableLLMCompression: Boolean,
    summaryTokenTarget: Int = 400
  ): ContextConfig =
    new ContextConfig(
      headroomPercent,
      maxSemanticBlocks,
      enableDeterministicCompression,
      enableLLMCompression,
      summaryTokenTarget
    )

  val default: ContextConfig = ContextConfig(
    headroomPercent = HeadroomPercent.Standard, // 8% headroom
    maxSemanticBlocks = 5,                      // Keep last 5 semantic blocks for history compression
    enableDeterministicCompression = true,
    enableLLMCompression = true,
    summaryTokenTarget = 400
  )
}

/**
 * Represents a single step in the context management pipeline
 */
@Experimental
final case class ContextStep private (
  stage: ContextStage,
  conversation: Conversation,
  tokensBefore: Int,
  tokensAfter: Int,
  applied: Boolean
) {

  /** Sets the pipeline stage. */
  def withStage(stage: ContextStage): ContextStep = copy(stage = stage)

  /** Sets the conversation after the step. */
  def withConversation(conversation: Conversation): ContextStep = copy(conversation = conversation)

  /** Sets the token count before the step. */
  def withTokensBefore(tokensBefore: Int): ContextStep = copy(tokensBefore = tokensBefore)

  /** Sets the token count after the step. */
  def withTokensAfter(tokensAfter: Int): ContextStep = copy(tokensAfter = tokensAfter)

  /** Sets whether the step changed the conversation. */
  def withApplied(applied: Boolean): ContextStep = copy(applied = applied)

  def summary: String =
    if (applied) f"$stage: $tokensBefore → $tokensAfter tokens (${tokensSaved} saved)"
    else f"$stage: skipped ($tokensBefore tokens)"

  def tokensSaved: Int         = tokensBefore - tokensAfter
  def compressionRatio: Double = tokensAfter.toDouble / tokensBefore
}

@Experimental
object ContextStep {

  /** Creates a [[ContextStep]]. */
  def apply(
    stage: ContextStage,
    conversation: Conversation,
    tokensBefore: Int,
    tokensAfter: Int,
    applied: Boolean
  ): ContextStep =
    new ContextStep(stage, conversation, tokensBefore, tokensAfter, applied)

  def noOperation(stage: ContextStage, conversation: Conversation, tokens: Int): ContextStep =
    ContextStep(stage, conversation, tokens, tokens, applied = false)
}

/**
 * Final result of context management pipeline
 */
@Experimental
final case class ManagedConversation private (
  conversation: Conversation,
  originalTokens: Int,
  finalTokens: Int,
  steps: Seq[ContextStep]
) {

  /** Sets the managed conversation. */
  def withConversation(conversation: Conversation): ManagedConversation = copy(conversation = conversation)

  /** Sets the token count before management. */
  def withOriginalTokens(originalTokens: Int): ManagedConversation = copy(originalTokens = originalTokens)

  /** Sets the token count after management. */
  def withFinalTokens(finalTokens: Int): ManagedConversation = copy(finalTokens = finalTokens)

  /** Sets the pipeline steps. */
  def withSteps(steps: Seq[ContextStep]): ManagedConversation = copy(steps = steps)

  def totalTokensSaved: Int           = originalTokens - finalTokens
  def overallCompressionRatio: Double = finalTokens.toDouble / originalTokens
  def stepsApplied: Seq[ContextStep]  = steps.filter(_.applied)

  def summary: String =
    f"Context management: $originalTokens → $finalTokens tokens " +
      f"(${(overallCompressionRatio * 100).toInt}%% remaining, ${stepsApplied.length} steps applied)"
}

@Experimental
object ManagedConversation {

  /** Creates a [[ManagedConversation]]. */
  def apply(
    conversation: Conversation,
    originalTokens: Int,
    finalTokens: Int,
    steps: Seq[ContextStep]
  ): ManagedConversation =
    new ManagedConversation(conversation, originalTokens, finalTokens, steps)
}
