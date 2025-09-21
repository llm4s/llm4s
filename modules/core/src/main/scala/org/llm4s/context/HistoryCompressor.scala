package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import scala.util.matching.Regex

/**
 * Deterministic history compression using structured digest extraction.
 * Replaces history blocks with compact digests containing key information:
 * - IDs, URLs, constraints, status codes, error messages
 * - Topic progressions, decision points, key facts
 * - Tool usage patterns and outcomes
 *
 * Strategy:
 *  - Keep the last K semantic blocks verbatim.
 *  - Replace all older blocks with one or more [HISTORY_SUMMARY] messages.
 *  - If the sum of digest messages exceeds `capTokens`, consolidate them into a single digest capped to `capTokens`.
 */
object HistoryCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  // Regex patterns for extracting structured information
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
   * @param messages      Full conversation messages (without system prompt injection).
   * @param tokenCounter  Token counter for the target model.
   * @param capTokens     Cap for the total size of digest message(s) (e.g., summaryTokenTarget).
   * @param keepLastK     Number of most recent semantic blocks to keep verbatim.
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
 * Structured information extracted from message blocks
 */
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
 * Compressed digest representation of a history block
 */
case class HistoryDigest(
  blockId: String,
  blockType: String,
  content: String,
  originalTokens: Int
)
