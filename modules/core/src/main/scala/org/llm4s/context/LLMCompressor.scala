package org.llm4s.context

import org.llm4s.error.ContextError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, CompressionRatio, TokenBudget }
import org.slf4j.LoggerFactory

/**
 * Implements digest-only compression for [HISTORY_SUMMARY] messages.
 * Replaces full LLM-powered compression with targeted digest compression.
 *
 * This is step 3 in the new 4-stage context management pipeline:
 * 1. Tool deterministic compaction
 * 2. History compression
 * 3. LLM digest squeeze (this module)
 * 4. Final token trim
 */
object LLMCompressor {
  private val logger = LoggerFactory.getLogger(getClass)

  private val DigestCompressionPrompt = """Compress this history digest while preserving key structured information:
- Keep all IDs, URLs, status codes, error messages
- Preserve decision points and outcomes  
- Maintain tool usage patterns
- Compress descriptive text only
- Target 50% size reduction"""

  /**
   * Apply digest-only LLM compression to [HISTORY_SUMMARY] messages only.
   * Leaves other message types unchanged.
   */
  def squeezeDigest(
    messages: Seq[Message],
    tokenCounter: ConversationTokenCounter,
    llmClient: LLMClient,
    capTokens: Int
  ): Result[Seq[Message]] = {
    logger.info(s"Starting LLM digest squeeze with cap: $capTokens tokens")

    // Find [HISTORY_SUMMARY] messages first
    val (digestMessages, otherMessages) = messages.partition(isHistoryDigestMessage)

    if (digestMessages.isEmpty) {
      logger.debug("No [HISTORY_SUMMARY] messages found for digest compression")
      return Right(messages)
    }

    // Compare digest tokens to the digest cap
    val digestTokens = digestMessages.map(tokenCounter.countMessage).sum
    if (digestTokens <= capTokens) {
      logger.debug(s"Digest already within cap: $digestTokens <= $capTokens tokens")
      return Right(messages)
    }

    logger.info(s"Found ${digestMessages.length} [HISTORY_SUMMARY] messages to squeeze")

    for {
      compressedDigests <- compressDigestMessages(digestMessages, llmClient)
      finalMessages     = otherMessages ++ compressedDigests
      finalDigestTokens = compressedDigests.map(tokenCounter.countMessage).sum
      _                 = logger.info(s"Digest squeeze complete: $digestTokens → $finalDigestTokens tokens")
    } yield finalMessages
  }

  /**
   * @deprecated Use squeezeDigest for new context management pipeline
   */
  @deprecated("Use squeezeDigest for new context management pipeline", "0.9.0")
  def compress(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    llmClient: LLMClient,
    targetBudget: TokenBudget,
    customPrompt: Option[String] = None
  ): Result[LLMCompressedConversation] = {
    // Keep original implementation for backward compatibility during transition
    logger.warn("Using deprecated compress method - switch to squeezeDigest for new pipeline")
    compressLegacy(conversation, tokenCounter, llmClient, targetBudget, customPrompt)
  }

  private def isHistoryDigestMessage(message: Message): Boolean =
    message.content.contains("[HISTORY_SUMMARY]")

  private def compressDigestMessages(
    digestMessages: Seq[Message],
    llmClient: LLMClient
  ): Result[Seq[Message]] = {
    // Compress each digest message individually to maintain structure
    val compressionResults = digestMessages.map(message => compressSingleDigest(message, llmClient))

    // Check if all compressions succeeded
    compressionResults.find(_.isLeft) match {
      case Some(error) => error.asInstanceOf[Result[Seq[Message]]]
      case None        => Right(compressionResults.map(_.toOption.get))
    }
  }

  private def compressSingleDigest(
    digestMessage: Message,
    llmClient: LLMClient
  ): Result[Message] = {
    val digestContent = extractDigestContent(digestMessage.content)

    val compressionConversation = Conversation(
      Seq(
        SystemMessage(DigestCompressionPrompt),
        UserMessage(digestContent)
      )
    )

    logger.debug("Sending digest to LLM for compression")

    llmClient
      .complete(compressionConversation)
      .left
      .map { error =>
        logger.error(s"Digest compression failed: ${error.message}")
        ContextError.llmCompressionFailed("digest", s"LLM call failed: ${error.message}")
      }
      .map { completion =>
        val compressedDigest = s"[HISTORY_SUMMARY]\n${completion.message.content}"
        digestMessage match {
          case _: SystemMessage => SystemMessage(compressedDigest)
          case _: UserMessage   => UserMessage(compressedDigest)
          case _                => UserMessage(compressedDigest) // Fallback
        }
      }
  }

  private def extractDigestContent(messageContent: String): String =
    messageContent.split("\n", 2) match {
      case Array(header, content) if header.contains("[HISTORY_SUMMARY]") => content
      case _                                                              => messageContent
    }

  // Legacy implementation for backward compatibility
  private def compressLegacy(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    llmClient: LLMClient,
    targetBudget: TokenBudget,
    customPrompt: Option[String]
  ): Result[LLMCompressedConversation] = {
    logger.info(s"Starting legacy LLM compression with target budget: $targetBudget tokens")

    for {
      _                 <- validateCompressionRequest(conversation, tokenCounter, targetBudget)
      contentToCompress <- prepareContentForCompression(conversation)
      compressionPrompt <- Right(customPrompt.getOrElse(DigestCompressionPrompt))
      compressedContent <- performLLMCompression(llmClient, contentToCompress, compressionPrompt)
      finalConversation <- reconstructConversation(compressedContent)
      result            <- validateCompressionResult(conversation, finalConversation, tokenCounter, targetBudget)
    } yield result
  }

  private def validateCompressionRequest(
    conversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    targetBudget: TokenBudget
  ): Result[Unit] = {
    val currentTokens = tokenCounter.countConversation(conversation)

    currentTokens match {
      case tokens if tokens <= targetBudget =>
        Left(
          ContextError.compressionFailed(
            "LLMCompressor",
            s"Conversation already fits budget: $tokens <= $targetBudget tokens"
          )
        )
      case _ if targetBudget <= 0 =>
        Left(
          ContextError.compressionFailed(
            "LLMCompressor",
            s"Invalid target budget: $targetBudget (must be positive)"
          )
        )
      case _ =>
        Right(())
    }
  }

  private def prepareContentForCompression(
    conversation: Conversation
  ): Result[String] = {
    // Since system message is now injected at API call time, work with all messages in conversation
    val content = conversation.messages.map(formatMessageForCompression).mkString("\n\n")
    logger.debug(s"Prepared ${conversation.messages.length} messages for compression")
    Right(content)
  }

  private def formatMessageForCompression(message: Message): String =
    message match {
      case UserMessage(content)             => s"USER: $content"
      case msg: AssistantMessage            => s"ASSISTANT: ${msg.content}"
      case ToolMessage(content, toolCallId) => s"TOOL[$toolCallId]: $content"
      case other                            => s"${other.getClass.getSimpleName.toUpperCase}: ${other.content}"
    }

  private def performLLMCompression(
    client: LLMClient,
    content: String,
    prompt: String
  ): Result[String] = {
    val compressionConversation = Conversation(
      Seq(
        SystemMessage(prompt),
        UserMessage(content)
      )
    )

    logger.debug("Sending conversation to LLM for compression")

    client
      .complete(compressionConversation)
      .left
      .map { error =>
        logger.error(s"LLM compression failed: ${error.message}")
        ContextError.llmCompressionFailed("unknown", s"LLM call failed: ${error.message}")
      }
      .map { completion =>
        logger.info("LLM compression completed successfully")
        completion.message.content
      }
  }

  private def reconstructConversation(
    compressedContent: String
  ): Result[Conversation] =
    parseCompressedContent(compressedContent) match {
      case Right(messages) =>
        Right(Conversation(messages))
      case Left(error) =>
        logger.warn(s"Failed to parse compressed content: $error, using single message fallback")
        val fallbackMessage = UserMessage(s"[COMPRESSED CONTEXT]\n$compressedContent")
        Right(Conversation(Seq(fallbackMessage)))
    }

  private def parseCompressedContent(content: String): Result[Seq[Message]] = {
    val lines = content.split("\n").map(_.trim).filter(_.nonEmpty)

    val messages = lines.foldLeft(Seq.empty[Message]) { (acc, line) =>
      parseMessageLine(line) match {
        case Some(message) => acc :+ message
        case None          => appendToLastMessage(acc, line)
      }
    }

    validateParsedMessages(messages)
  }

  private def parseMessageLine(line: String): Option[Message] =
    line match {
      case userLine if userLine.startsWith("USER:") =>
        Some(UserMessage(userLine.drop(5).trim))
      case assistantLine if assistantLine.startsWith("ASSISTANT:") =>
        Some(AssistantMessage(assistantLine.drop(10).trim))
      case _ =>
        None
    }

  private def appendToLastMessage(acc: Seq[Message], line: String): Seq[Message] =
    acc.lastOption match {
      case Some(UserMessage(content)) =>
        acc.init :+ UserMessage(s"$content\n$line")
      case Some(msg: AssistantMessage) =>
        acc.init :+ msg.copy(contentOpt = Some(s"${msg.content}\n$line"))
      case _ =>
        acc :+ UserMessage(line)
    }

  private def validateParsedMessages(messages: Seq[Message]): Result[Seq[Message]] =
    messages match {
      case empty if empty.isEmpty =>
        Left(ContextError.compressionFailed("LLMCompressor", "No messages parsed from compressed content"))
      case nonEmpty =>
        Right(nonEmpty)
    }

  private def validateCompressionResult(
    originalConversation: Conversation,
    compressedConversation: Conversation,
    tokenCounter: ConversationTokenCounter,
    targetBudget: TokenBudget
  ): Result[LLMCompressedConversation] = {
    val originalTokens   = tokenCounter.countConversation(originalConversation)
    val compressedTokens = tokenCounter.countConversation(compressedConversation)
    val compressionRatio = compressedTokens.toDouble / originalTokens

    compressedTokens match {
      case tokens if tokens <= targetBudget =>
        logger.info(s"LLM compression successful: $originalTokens → $tokens tokens ($compressionRatio ratio)")
        Right(
          LLMCompressedConversation(
            conversation = compressedConversation,
            originalTokens = originalTokens,
            compressedTokens = compressedTokens,
            compressionRatio = compressionRatio,
            targetBudget = targetBudget,
            budgetAchieved = true
          )
        )
      case tokens =>
        logger.warn(s"LLM compression didn't achieve target: $tokens > $targetBudget tokens")
        Right(
          LLMCompressedConversation(
            conversation = compressedConversation,
            originalTokens = originalTokens,
            compressedTokens = compressedTokens,
            compressionRatio = compressionRatio,
            targetBudget = targetBudget,
            budgetAchieved = false
          )
        )
    }
  }
}

/**
 * Result of LLM-powered compression process
 */
case class LLMCompressedConversation(
  conversation: Conversation,
  originalTokens: Int,
  compressedTokens: Int,
  compressionRatio: CompressionRatio,
  targetBudget: TokenBudget,
  budgetAchieved: Boolean
) {
  def tokensSaved: Int           = originalTokens - compressedTokens
  def compressionPercentage: Int = ((1.0 - compressionRatio) * 100).toInt

  def summary: String = {
    val status = if (budgetAchieved) "SUCCESS" else "PARTIAL"
    f"LLM compression [$status]: $originalTokens → $compressedTokens tokens " +
      f"($compressionPercentage%% reduction, target: $targetBudget)"
  }
}
