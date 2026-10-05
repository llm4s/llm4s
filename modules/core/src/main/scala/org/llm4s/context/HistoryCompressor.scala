package org.llm4s.context

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import scala.util.matching.Regex

/**
 * Deterministic history compression using structured digest extraction.
 *
 * This compressor creates compact `[HISTORY_SUMMARY]` digests from older conversation
 * blocks, preserving recent context verbatim while summarizing history. The digests
 * extract key structured information that's likely to be referenced later.
 *
 * ==Compression Strategy==
 *
 * The compressor uses a "keep last K" strategy:
 *
 * 1. '''Group''' messages into semantic blocks (user-assistant pairs)
 * 2. '''Keep''' the last K blocks verbatim (recent context)
 * 3. '''Digest''' older blocks into `[HISTORY_SUMMARY]` messages
 * 4. '''Consolidate''' if total digest size exceeds the cap
 *
 * ==Information Extraction==
 *
 * Each digest extracts structured information using regex patterns:
 *
 *  - '''Identifiers''': IDs, UUIDs, keys, references
 *  - '''URLs''': HTTP/HTTPS links
 *  - '''Constraints''': Must/should/cannot requirements
 *  - '''Status Codes''': HTTP status codes, error codes
 *  - '''Errors''': Error messages and exceptions
 *  - '''Decisions''': "decided", "chosen", "selected" statements
 *  - '''Tool Usage''': Function/API call mentions
 *  - '''Outcomes''': Results, conclusions, completions
 *
 * ==Idempotency==
 *
 * The compressor is '''idempotent''': if messages already contain `[HISTORY_SUMMARY]`
 * markers, they are returned unchanged. This allows safe re-application.
 *
 * @example
 * {{{
 * val compressed = HistoryCompressor.compressToDigest(
 *   messages = conversation.messages,
 *   tokenCounter = counter,
 *   capTokens = 400,  // Max tokens for digests
 *   keepLastK = 3     // Keep last 3 blocks verbatim
 * )
 * }}}
 *
 * @see [[SemanticBlocks]] for the block grouping algorithm
 * @see [[StructuredInfo]] for the extracted information types
 */
@Stable
object HistoryCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  // ==================== Regex Patterns for Information Extraction ====================

  /** Matches ID/key/UUID assignments like "id: ABC123" or "key=XYZ" */
  private val IdentifierPattern: Regex = """(?i)\b(?:id|identifier|uuid|key|ref(?:erence)?)[:\s=]+([a-zA-Z0-9\-_]+)""".r
  private val UrlPattern: Regex        = """(?i)(?:https?://|www\.)[^\s<>"'{|}|\\^`\[\]]+""".r
  private val ConstraintPattern: Regex = """(?i)(?:must|should|cannot|required?|forbidden|allowed)[^.!?]*[.!?]""".r
  private val StatusCodePattern: Regex = """(?i)(?:status|code|error)[:\s]+(\d{3,4})""".r
  private val ErrorMessagePattern: Regex = """(?i)(?:error|exception|failed?|denied)[^.!?]*[.!?]""".r
  private val DecisionPattern: Regex     = """(?i)(?:decided|chosen|selected|determined)[^.!?]*[.!?]""".r
  private val ToolPattern: Regex         = """(?i)(?:tool|function|api|call)(?:ed|ing)?[^.!?]*[.!?]""".r
  private val OutcomePattern: Regex      = """(?i)(?:result|outcome|conclusion|success|completed?)[^.!?]*[.!?]""".r

  /**
   * Compress history into digest(s), keeping the last `keepLastK` semantic blocks intact.
   *
   * The compression process:
   * 1. Groups messages into semantic blocks
   * 2. Splits blocks into "older" (to digest) and "recent" (to keep)
   * 3. Creates `[HISTORY_SUMMARY]` messages for older blocks
   * 4. Consolidates digests if they exceed `capTokens`
   *
   * @param messages Full conversation messages (without system prompt injection)
   * @param tokenCounter Token counter for the target model
   * @param capTokens Maximum tokens for all digest messages combined
   * @param keepLastK Number of most recent semantic blocks to keep verbatim
   * @return Compressed messages with digests followed by recent blocks
   */
  def compressToDigest(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    capTokens: Int,
    keepLastK: Int
  ): Result[Seq[Message]] = {
    logger.debug(s"Starting history compression: cap=$capTokens, keepLastK=$keepLastK")

    // Skip compression if messages already contain [HISTORY_SUMMARY] for idempotence
    val existingSummaries = messages.filter(_.content.startsWith("[HISTORY_SUMMARY]"))
    if (existingSummaries.nonEmpty) {
      logger.debug(s"Found ${existingSummaries.length} existing history summaries, skipping compression")
      Right(messages)
    } else {
      for {
        semanticBlocks <- SemanticBlocks.groupIntoSemanticBlocks(messages)
        digestMessages <- createDigestMessages(semanticBlocks, tokenCounter, capTokens, keepLastK)
      } yield digestMessages
    }
  }

  private def createDigestMessages(
    blocks: Seq[SemanticBlock],
    tokenCounter: ConversationTokenCounter,
    capTokens: Int,
    keepLastK: Int
  ): Result[Seq[Message]] = {
    // Split into older blocks (to be summarized) and recent blocks (kept verbatim)
    val (older, recent) =
      if (keepLastK <= 0) (blocks, Seq.empty)
      else blocks.splitAt(math.max(0, blocks.length - keepLastK))

    val olderDigests = older.map(createBlockDigest)

    val digestMessages: Seq[Message] =
      olderDigests.map(d => SystemMessage(s"[HISTORY_SUMMARY]\n${d.content}"))

    val recentMessages: Seq[Message] =
      recent.flatMap(_.messages)

    // If multiple digest messages exceed cap, consolidate them into one within cap.
    val digestTotalTokens = digestMessages.map(tokenCounter.countMessage).sum
    val finalDigestMsgs: Seq[Message] =
      if (digestTotalTokens <= capTokens) digestMessages
      else {
        val consolidated = consolidateDigests(olderDigests, capTokens)
        Seq(SystemMessage(s"[HISTORY_SUMMARY]\n${consolidated.content}"))
      }

    Right(finalDigestMsgs ++ recentMessages)
  }

  private def createBlockDigest(block: SemanticBlock): HistoryDigest = {
    val allContent = block.messages.map(_.content).mkString(" ")

    val extractedInfo = StructuredInfo(
      identifiers = extractMatches(IdentifierPattern, allContent).take(3),
      urls = extractMatches(UrlPattern, allContent).take(2),
      constraints = extractMatches(ConstraintPattern, allContent).take(2),
      statusCodes = extractMatches(StatusCodePattern, allContent).take(2),
      errors = extractMatches(ErrorMessagePattern, allContent).take(2),
      decisions = extractMatches(DecisionPattern, allContent).take(2),
      toolUsage = extractMatches(ToolPattern, allContent).take(2),
      outcomes = extractMatches(OutcomePattern, allContent).take(2)
    )

    val digestContent = formatDigest(block, extractedInfo)

    HistoryDigest(
      blockId = block.id.toString,
      blockType = block.blockType.toString,
      content = digestContent,
      originalTokens = allContent.length / 4 // Rough token estimate
    )
  }

  private def extractMatches(pattern: Regex, content: String): Seq[String] =
    pattern.findAllIn(content).toSeq.distinct.take(5) // Limit matches per pattern

  private def formatDigest(block: SemanticBlock, info: StructuredInfo): String = {
    val builder = new StringBuilder()

    builder.append(s"${block.blockType}: ")

    // Add key identifiers
    if (info.identifiers.nonEmpty) {
      builder.append(s"IDs[${info.identifiers.mkString(",")}] ")
    }

    // Add constraints and decisions
    if (info.constraints.nonEmpty) {
      builder.append(s"Rules[${info.constraints.head.take(50)}...] ")
    }
    if (info.decisions.nonEmpty) {
      builder.append(s"Decision[${info.decisions.head.take(50)}...] ")
    }

    // Add errors and status codes
    if (info.errors.nonEmpty) {
      builder.append(s"Error[${info.errors.head.take(40)}...] ")
    }
    if (info.statusCodes.nonEmpty) {
      builder.append(s"Status[${info.statusCodes.mkString(",")}] ")
    }

    // Add tool usage and outcomes
    if (info.toolUsage.nonEmpty) {
      builder.append(s"Tools[${info.toolUsage.length} used] ")
    }
    if (info.outcomes.nonEmpty) {
      builder.append(s"Result[${info.outcomes.head.take(40)}...] ")
    }

    // Add URLs if present
    if (info.urls.nonEmpty) {
      builder.append(s"URLs[${info.urls.length}] ")
    }

    val digest = builder.toString.trim
    if (digest.endsWith(":")) digest.dropRight(1) + " (no key info extracted)"
    else digest
  }

  private def consolidateDigests(digests: Seq[HistoryDigest], targetTokens: Int): HistoryDigest = {
    val consolidatedContent = digests.zipWithIndex
      .map { case (digest, idx) =>
        s"Block${idx + 1}: ${digest.content}"
      }
      .mkString("; ")

    val truncated =
      if (consolidatedContent.length > targetTokens * 4) // ~4 chars per token
        consolidatedContent.take(targetTokens * 4) + "..."
      else
        consolidatedContent

    HistoryDigest(
      blockId = s"consolidated-${digests.length}",
      blockType = "ConsolidatedHistory",
      content = truncated,
      originalTokens = digests.map(_.originalTokens).sum
    )
  }
}

/**
 * Structured information extracted from a message block.
 *
 * Each field contains strings matched by the corresponding regex pattern
 * in [[HistoryCompressor]]. Matches are limited to prevent digest bloat.
 *
 * @param identifiers IDs, UUIDs, keys found in the content
 * @param urls HTTP/HTTPS URLs
 * @param constraints Requirement statements (must, should, cannot)
 * @param statusCodes HTTP status codes, error codes
 * @param errors Error messages and exception info
 * @param decisions Decision statements
 * @param toolUsage Tool/function/API call mentions
 * @param outcomes Result and conclusion statements
 */
@Stable
case class StructuredInfo(
  identifiers: Seq[String],
  urls: Seq[String],
  constraints: Seq[String],
  statusCodes: Seq[String],
  errors: Seq[String],
  decisions: Seq[String],
  toolUsage: Seq[String],
  outcomes: Seq[String]
)

/**
 * Compressed digest representation of a history block.
 *
 * Contains the formatted summary text and metadata about the original block.
 *
 * @param blockId Original semantic block ID
 * @param blockType Type of the block (UserAssistantPair, StandaloneTool, etc.)
 * @param content Formatted digest text for inclusion in conversation
 * @param originalTokens Estimated token count of original content
 */
@Stable
case class HistoryDigest(
  blockId: String,
  blockType: String,
  content: String,
  originalTokens: Int
)
