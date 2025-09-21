package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Implements rule-based deterministic compression for conversation context.
 * Applies various cleanup strategies to reduce token usage while preserving meaning.
 *
 * Compression rules:
 * 1. Remove redundant phrases and filler words
 * 2. Compress repetitive explanations
 * 3. Truncate overly verbose responses
 * 4. Consolidate similar questions/responses
 * 5. Remove excessive formatting and examples
 */
object DeterministicCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Orchestrator: delegate to "tool pass" then (optionally) "subjective pass". */
  def compressToCap(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    capTokens: Int,
    artifactStore: Option[ArtifactStore] = None,
    enableSubjectiveEdits: Boolean = false
  ): Result[Seq[Message]] = {
    val store = artifactStore.getOrElse(ArtifactStore.inMemory())
    val start = countTokens(messages, tokenCounter)
    logger.debug(s"Starting compression to cap=$capTokens (start=$start)")

    for {
      afterTool       <- toolCompaction(messages, store)
      afterSubjective <- subjectivePassIfNeeded(afterTool, tokenCounter, capTokens, enableSubjectiveEdits)
    } yield {
      val end = countTokens(afterSubjective, tokenCounter)
      logger.info(s"Compression complete: $start → $end tokens")
      afterSubjective
    }
  }

  /** Step 1: single tool-output compaction pass (schema/log/binary aware). */
  private def toolCompaction(
    messages: Seq[Message],
    store: ArtifactStore
  ): Result[Seq[Message]] =
    ToolOutputCompressor
      .compressToolOutputs(messages, store)
      .map { out =>
        logger.debug("Tool compaction done")
        out
      }

  /**
   * Step 2 (optional): run subjective rules until we’re ≤ cap.
   * Skips if subjective edits are disabled or we’re already within the cap.
   */
  private def subjectivePassIfNeeded(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    capTokens: Int,
    enableSubjectiveEdits: Boolean
  ): Result[Seq[Message]] = {
    val tokens = countTokens(messages, tokenCounter)
    if (!enableSubjectiveEdits || tokens <= capTokens) Right(messages)
    else applySubjectiveRulesToCap(messages, tokenCounter, capTokens)
  }

  /** Apply only the subjective rules (no tool rule) until we meet the cap. */
  private def applySubjectiveRulesToCap(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    capTokens: Int
  ): Result[Seq[Message]] = {
    @scala.annotation.tailrec
    def loop(rules: Seq[CompressionRule], curr: Seq[Message], currTokens: Int): Seq[Message] =
      if (currTokens <= capTokens || rules.isEmpty) curr
      else {
        val rule       = rules.head
        val next       = rule.apply(curr)
        val nextTokens = countTokens(next, tokenCounter)
        val (advance, tokens) =
          if (nextTokens < currTokens) (next, nextTokens) else (curr, currTokens)
        loop(rules.tail, advance, tokens)
      }

    val subjectiveRules = Seq(
      CompressionRule.removeFillerWords,
      CompressionRule.compressRepetitiveContent,
      CompressionRule.truncateVerboseResponses
      // If you want, you can also include:
      // CompressionRule.removeRedundantPhrases,
      // CompressionRule.consolidateExamples
    )

    val startTokens = countTokens(messages, tokenCounter)
    val out         = loop(subjectiveRules, messages, startTokens)
    Right(out)
  }

  /** Small helper for consistent token counting on sequences. */
  private def countTokens(messages: Seq[Message], counter: ConversationTokenCounter): Int =
    messages.map(counter.countMessage).sum
}

/**
 * Represents a compression rule that can be applied to messages
 */
case class CompressionRule(name: String, apply: Seq[Message] => Seq[Message])

object CompressionRule {

  val removeRedundantPhrases: CompressionRule = CompressionRule(
    "remove_redundant_phrases",
    messages => messages.map(compressRedundantPhrases)
  )

  val compressRepetitiveContent: CompressionRule = CompressionRule(
    "compress_repetitive_content",
    messages => messages.map(compressRepetitiveText)
  )

  val truncateVerboseResponses: CompressionRule = CompressionRule(
    "truncate_verbose_responses",
    messages => messages.map(truncateIfTooLong)
  )

  val removeFillerWords: CompressionRule = CompressionRule(
    "remove_filler_words",
    messages => messages.map(removeFillerWords)
  )

  val consolidateExamples: CompressionRule = CompressionRule(
    "consolidate_examples",
    messages => consolidateSimilarExamples(messages)
  )

  def compressToolOutputs(artifactStore: ArtifactStore): CompressionRule = CompressionRule(
    "compress_tool_outputs",
    messages => ToolOutputCompressor.compressToolOutputs(messages, artifactStore).getOrElse(messages)
  )

  private def compressRedundantPhrases(message: Message): Message =
    message match {
      case msg: UserMessage      => msg.copy(content = cleanupRedundancy(msg.content))
      case msg: AssistantMessage => msg.copy(contentOpt = Some(cleanupRedundancy(msg.content)))
      case other                 => other
    }

  private def compressRepetitiveText(message: Message): Message =
    message match {
      case msg: UserMessage      => msg.copy(content = reduceRepetition(msg.content))
      case msg: AssistantMessage => msg.copy(contentOpt = Some(reduceRepetition(msg.content)))
      case other                 => other
    }

  private def truncateIfTooLong(message: Message): Message = {
    val MaxTokens = 400 // Target token cap for verbose responses
    val MinTokens = 300 // Minimum tokens to preserve

    message match {
      case msg: AssistantMessage =>
        // Never truncate final answers or short messages, only intermediate verbose responses
        truncateAssistantMessage(msg, MaxTokens, MinTokens)
      case _ =>
        // Never truncate user input, system messages, or tool messages
        message
    }
  }

  private def truncateAssistantMessage(
    message: AssistantMessage,
    maxTokens: Int,
    minTokens: Int
  ): AssistantMessage = {
    val content         = message.content
    val estimatedTokens = estimateTokenCount(content)
    if (estimatedTokens <= maxTokens || estimatedTokens < minTokens * 2) message
    else {
      val summarized = summarizeContent(content, maxTokens)
      message.copy(contentOpt = Some(summarized))
    }
  }

  private def summarizeContent(content: String, targetTokens: Int): String = {
    val sentences = content.split("\\.\\s+").filter(_.trim.nonEmpty)
    sentences.length match {
      case 1           => truncateAtWordBoundary(content, targetTokens)
      case n if n <= 3 => content
      case n =>
        val firstSentence = sentences.head
        val lastSentence  = sentences.last
        val middleCount   = n - 2
        s"$firstSentence. ...[summarized $middleCount sentences]... $lastSentence."
    }
  }

  private def truncateAtWordBoundary(content: String, targetTokens: Int): String = {
    val approxCharsPerToken = 4
    val targetChars         = targetTokens * approxCharsPerToken
    if (content.length <= targetChars) content
    else {
      val truncated = content.take(targetChars)
      val lastSpace = truncated.lastIndexOf(' ')
      val finalCut  = if (lastSpace > targetChars * 0.8) truncated.take(lastSpace) else truncated
      s"$finalCut...[content capped for length]"
    }
  }

  private def estimateTokenCount(content: String): Int =
    (content.length / 3.5).toInt // ~1 token ≈ 3.5–4 chars

  private def removeFillerWords(message: Message): Message = {
    val fillerWords = Set("um", "uh", "well", "you know", "like", "actually", "basically")
    message match {
      case msg: UserMessage                                       => msg // never modify user input
      case msg: AssistantMessage if isPreciseContent(msg.content) => msg
      case msg: AssistantMessage if isTranscriptLike(msg.content) =>
        val cleaned = cleanFillerWords(msg.content, fillerWords)
        msg.copy(contentOpt = Some(cleaned))
      case other => other
    }
  }

  private def isPreciseContent(content: String): Boolean = {
    val lc = content.toLowerCase
    detectContentStructure(content, lc) match {
      case ContentStructure.Code | ContentStructure.Json | ContentStructure.Quoted | ContentStructure.Error |
          ContentStructure.Config =>
        true
      case ContentStructure.Text => false
    }
  }

  private def detectContentStructure(content: String, lc: String): ContentStructure =
    lc match {
      case c if c.contains("```")                                                             => ContentStructure.Code
      case _ if content.contains("{") && content.contains("}") && content.count(_ == '{') > 2 => ContentStructure.Json
      case _ if content.contains("\"") && content.count(_ == '"') > 4                         => ContentStructure.Quoted
      case c if c.contains("error:") || c.contains("exception")                               => ContentStructure.Error
      case _ if content.contains("=") && content.contains(":")                                => ContentStructure.Config
      case _                                                                                  => ContentStructure.Text
    }

  sealed private trait ContentStructure
  private object ContentStructure {
    case object Code   extends ContentStructure
    case object Json   extends ContentStructure
    case object Quoted extends ContentStructure
    case object Error  extends ContentStructure
    case object Config extends ContentStructure
    case object Text   extends ContentStructure
  }

  private def isTranscriptLike(content: String): Boolean = {
    val lc          = content.toLowerCase
    val fillerCount = Seq("um", "uh", "well", "you know", "like").count(lc.contains)
    fillerCount >= 2 ||
    lc.contains("let me think") ||
    lc.contains("so basically") ||
    lc.contains("i mean")
  }

  private def consolidateSimilarExamples(messages: Seq[Message]): Seq[Message] = {
    @scala.annotation.tailrec
    def groupExampleBlocks(
      remaining: Seq[Message],
      accumulated: Seq[Message],
      currentExampleBlock: Option[Seq[Message]]
    ): Seq[Message] =
      remaining match {
        case Nil =>
          currentExampleBlock match {
            case Some(examples) if examples.length >= 2 => accumulated :+ consolidateExampleBlock(examples)
            case Some(examples)                         => accumulated ++ examples
            case None                                   => accumulated
          }
        case msg +: tail if containsExample(msg) =>
          val newBlock = currentExampleBlock.getOrElse(Seq.empty) :+ msg
          groupExampleBlocks(tail, accumulated, Some(newBlock))
        case msg +: tail =>
          currentExampleBlock match {
            case Some(examples) if examples.length >= 2 =>
              val consolidated = consolidateExampleBlock(examples)
              groupExampleBlocks(tail, accumulated :+ consolidated :+ msg, None)
            case Some(examples) =>
              groupExampleBlocks(tail, accumulated ++ examples :+ msg, None)
            case None =>
              groupExampleBlocks(tail, accumulated :+ msg, None)
          }
      }

    groupExampleBlocks(messages, Seq.empty, None)
  }

  private def consolidateExampleBlock(examples: Seq[Message]): Message = {
    val exampleContents = examples.map(_.content)
    val n               = exampleContents.length
    if (n == 1) examples.head
    else {
      val first = exampleContents.head
      val last  = exampleContents.last
      val consolidatedContent =
        if (n == 2)
          s"[Examples consolidated - 2 total]\n\nExample 1: $first\n\nExample 2: $last"
        else {
          val middleCount = n - 2
          s"[Examples consolidated - $n total]\n\nFirst: $first\n\n...[+ $middleCount additional examples]...\n\nLast: $last"
        }

      examples.head match {
        case _: UserMessage        => UserMessage(consolidatedContent)
        case msg: AssistantMessage => msg.copy(contentOpt = Some(consolidatedContent))
        case tool: ToolMessage     => tool.copy(content = consolidatedContent)
        case _                     => UserMessage(consolidatedContent)
      }
    }
  }

  private def cleanupRedundancy(content: String): String = {
    val redundantPhrases = Seq(
      "as I mentioned before",
      "like I said",
      "to reiterate",
      "again",
      "as previously stated",
      "to repeat",
      "once more"
    )
    redundantPhrases.foldLeft(content)((text, phrase) => text.replaceAll(s"(?i)\\b$phrase\\b,?\\s*", "")).trim
  }

  private def reduceRepetition(content: String): String = {
    val sentences = content.split("\\.\\s+").filter(_.trim.nonEmpty)
    if (sentences.length <= 1) content
    else deduplicateWithCounts(sentences.toIndexedSeq).mkString(". ") + "."
  }

  private def deduplicateWithCounts(sentences: Seq[String]): Seq[String] = {
    val (_, deduped) = sentences.foldLeft((Map.empty[String, Int], Seq.empty[String])) { case ((seen, acc), sentence) =>
      val normalized = normalizeSentence(sentence)
      seen.get(normalized) match {
        case None => (seen + (normalized -> 1), acc :+ sentence)
        case Some(count) =>
          val newCount        = count + 1
          val countedSentence = s"$sentence [×$newCount]"
          val updatedAcc      = if (acc.nonEmpty) acc.init :+ countedSentence else Seq(countedSentence)
          (seen + (normalized -> newCount), updatedAcc)
      }
    }
    deduped
  }

  private def normalizeSentence(sentence: String): String =
    sentence.toLowerCase
      .replaceAll("\\s+", " ")
      .replaceAll("[.,;:!?]+$", "")
      .replaceAll("^[\\s\\-•]+", "")
      .trim

  private def cleanFillerWords(content: String, fillers: Set[String]): String =
    fillers
      .foldLeft(content)((text, filler) => text.replaceAll(s"(?i)\\b$filler\\b,?\\s*", ""))
      .replaceAll("\\s+", " ")
      .trim

  private def containsExample(message: Message): Boolean = {
    val lc = message.content.toLowerCase
    lc.contains("example") || lc.contains("for instance")
  }
}
