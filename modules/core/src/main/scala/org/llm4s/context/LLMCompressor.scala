package org.llm4s.context

import org.llm4s.error.ContextError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Applies LLM-powered compression to `[HISTORY_SUMMARY]` digest messages.
 *
 * This is Step 3 in the 4-stage context management pipeline. It targets only the
 * structured digest messages produced by [[HistoryCompressor]] — it never touches
 * user messages, assistant messages, or tool outputs directly.
 *
 * ==When to Use==
 *
 * Use `LLMCompressor` (via [[ContextManager]] with `enableLLMCompression = true`) when:
 *  - The conversation is long-running and history digests have grown too large to fit
 *    within the remaining token budget after [[HistoryCompressor]] has run.
 *  - Preserving semantic fidelity in the digest is important — you cannot afford to
 *    simply truncate or drop structured information (IDs, decisions, error codes).
 *  - An extra LLM API call per compression event is acceptable (latency and cost).
 *
 * ==When NOT to Use==
 *
 * Prefer [[DeterministicCompressor]] or [[HistoryCompressor]] alone when:
 *  - Latency is critical (e.g., interactive chatbots where each round-trip matters).
 *  - Cost per token must be minimised — an extra inference call adds to compression cost.
 *  - The conversation fits within budget after deterministic steps; `ContextManager`
 *    skips this step automatically in that case.
 *
 * ==Cost==
 *
 * When the combined size of all `[HISTORY_SUMMARY]` messages exceeds the cap,
 * [[squeezeDigest]] makes one LLM API call per `[HISTORY_SUMMARY]` message (the cap
 * is a combined budget, not a per-message threshold). Budget for this accordingly.
 *
 * ==Pipeline Position==
 *
 * {{{
 * Step 1: DeterministicCompressor — free, fast, tool-output focused
 * Step 2: HistoryCompressor       — free, fast, deterministic digest
 * Step 3: LLMCompressor           — 1 LLM call per digest, slower, high quality  ← this object
 * Step 4: TokenWindow.trimToBudget — free, last resort
 * }}}
 *
 * @see [[HistoryCompressor]] for digest generation that this compressor further shrinks
 * @see [[DeterministicCompressor]] for the cheaper alternative with no API calls
 * @see [[ContextManager]] for the orchestrator that chooses when to invoke each step
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
   * Compresses `[HISTORY_SUMMARY]` digest messages using an LLM, leaving all other
   * message types (user, assistant, tool) completely untouched.
   *
   * If no digest messages are found, or if their combined token count already fits
   * within `capTokens`, the original messages are returned unchanged with no API call.
   *
   * @param messages     Full conversation message sequence (digests interleaved with others)
   * @param tokenCounter Token counter calibrated to the target model's tokenizer
   * @param llmClient    LLM client used to perform the compression inference call
   * @param capTokens    Maximum allowed tokens for all digest messages combined
   * @return             Compressed messages on success, or a
   *                     [[org.llm4s.error.ContextError]] if the LLM call fails
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

  private def isHistoryDigestMessage(message: Message): Boolean =
    message.content.contains("[HISTORY_SUMMARY]")

  private def compressDigestMessages(
    digestMessages: Seq[Message],
    llmClient: LLMClient
  ): Result[Seq[Message]] =
    digestMessages
      .map(compressSingleDigest(_, llmClient))
      .foldLeft[Result[Seq[Message]]](Right(Seq.empty)) {
        case (Right(acc), Right(msg)) => Right(acc :+ msg)
        case (Left(err), _)           => Left(err)
        case (_, Left(err))           => Left(err)
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

}
