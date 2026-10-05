package org.llm4s.context

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, SemanticBlockId }
import org.slf4j.LoggerFactory

/**
 * Groups messages into semantic blocks for context compression and history management.
 *
 * ==Semantic Block Concept==
 *
 * A semantic block represents a logically related group of messages in a conversation.
 * The primary patterns are:
 *
 *  - '''User-Assistant Pairs''': A user question followed by an assistant response.
 *    These form the natural "turns" of a conversation.
 *
 *  - '''Tool Interactions''': Tool calls and their results, often associated with
 *    an assistant message that triggered them.
 *
 *  - '''Standalone Messages''': Messages that don't fit the pair pattern (e.g.,
 *    system messages, isolated assistant responses).
 *
 * ==Algorithm==
 *
 * The grouping algorithm uses a tail-recursive state machine:
 *
 * 1. '''UserMessage''': Starts a new block expecting an assistant response
 * 2. '''AssistantMessage''': Completes a user block, or becomes standalone
 * 3. '''ToolMessage''': Attaches to the current block or becomes standalone
 * 4. '''SystemMessage''': Treated similarly to assistant (can complete blocks)
 *
 * @example
 * {{{
 * val messages = Seq(
 *   UserMessage("What's the weather?"),
 *   AssistantMessage("I'll check for you..."),
 *   ToolMessage("""{"temp": 72}""", "call_1"),
 *   AssistantMessage("It's 72 degrees.")
 * )
 * val blocks = SemanticBlocks.groupIntoSemanticBlocks(messages)
 * // Result: One UserAssistantPair block containing all 4 messages
 * }}}
 *
 * @see [[HistoryCompressor]] which uses semantic blocks for history compression
 * @see [[SemanticBlockType]] for the classification of block types
 */
@Stable
object SemanticBlocks {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Group messages into semantic blocks.
   *
   * The algorithm processes messages sequentially, maintaining state about the
   * current block being built. User messages start new blocks, assistant messages
   * complete them, and tool messages are attached to existing blocks.
   *
   * @param messages The conversation messages to group
   * @return A sequence of semantic blocks, or an error if grouping fails
   */
  def groupIntoSemanticBlocks(messages: Seq[Message]): Result[Seq[SemanticBlock]] = {
    @scala.annotation.tailrec
    def groupMessages(
      remaining: Seq[Message],
      accumulated: Seq[SemanticBlock],
      currentBlock: Option[SemanticBlock]
    ): Seq[SemanticBlock] =
      remaining match {
        case Nil =>
          currentBlock.map(accumulated :+ _).getOrElse(accumulated)

        case (userMsg: UserMessage) +: tail =>
          val newBlock           = SemanticBlock.startUserBlock(userMsg)
          val updatedAccumulated = currentBlock.map(accumulated :+ _).getOrElse(accumulated)
          groupMessages(tail, updatedAccumulated, Some(newBlock))

        case (assistantMsg: AssistantMessage) +: tail =>
          currentBlock match {
            case Some(block) if block.expectingAssistantResponse =>
              val completedBlock = block.addAssistantMessage(assistantMsg)
              groupMessages(tail, accumulated :+ completedBlock, None)
            case Some(block) =>
              val newBlock = SemanticBlock.standaloneAssistant(assistantMsg)
              groupMessages(tail, accumulated :+ block, Some(newBlock))
            case None =>
              val newBlock = SemanticBlock.standaloneAssistant(assistantMsg)
              groupMessages(tail, accumulated, Some(newBlock))
          }

        case (toolMsg: ToolMessage) +: tail =>
          currentBlock match {
            case Some(block) =>
              val updatedBlock = block.addToolMessage(toolMsg)
              groupMessages(tail, accumulated, Some(updatedBlock))
            case None =>
              val newBlock = SemanticBlock.standaloneTool(toolMsg)
              groupMessages(tail, accumulated, Some(newBlock))
          }

        case (systemMsg: SystemMessage) +: tail =>
          // Treat SystemMessage as AssistantMessage to avoid warnings
          currentBlock match {
            case Some(block) if block.expectingAssistantResponse =>
              val completedBlock = block.addSystemMessage(systemMsg)
              groupMessages(tail, accumulated :+ completedBlock, None)
            case Some(block) =>
              val newBlock = SemanticBlock.standaloneSystem(systemMsg)
              groupMessages(tail, accumulated :+ block, Some(newBlock))
            case None =>
              val newBlock = SemanticBlock.standaloneSystem(systemMsg)
              groupMessages(tail, accumulated, Some(newBlock))
          }

      }

    val blocks = groupMessages(messages, Seq.empty, None)
    logger.debug(s"Grouped ${messages.length} messages into ${blocks.length} semantic blocks")
    Right(blocks)
  }
}

/**
 * Represents a semantic block of related messages in a conversation.
 *
 * A semantic block groups logically related messages together, typically
 * a user-assistant exchange with any associated tool calls.
 *
 * @param id Unique identifier for this block
 * @param messages The messages contained in this block
 * @param blockType The classification of this block (pair, standalone, etc.)
 * @param expectingAssistantResponse True if the block is incomplete (awaiting response)
 */
@Stable
case class SemanticBlock(
  id: SemanticBlockId,
  messages: Seq[Message],
  blockType: SemanticBlockType,
  expectingAssistantResponse: Boolean = false
) {
  def addAssistantMessage(msg: AssistantMessage): SemanticBlock =
    copy(messages = messages :+ msg, expectingAssistantResponse = false)

  def addSystemMessage(msg: SystemMessage): SemanticBlock =
    copy(messages = messages :+ msg, expectingAssistantResponse = false)

  def addToolMessage(msg: ToolMessage): SemanticBlock =
    copy(messages = messages :+ msg)

  def getBlockSummary: String =
    blockType match {
      case SemanticBlockType.UserAssistantPair =>
        val userContent = messages.headOption.map(_.content.take(50)).getOrElse("No content")
        s"Q&A pair: '$userContent...'"
      case SemanticBlockType.StandaloneAssistant =>
        s"Assistant message: '${messages.head.content.take(50)}...'"
      case SemanticBlockType.StandaloneTool =>
        s"Tool interaction: ${messages.head.getClass.getSimpleName}"
      case SemanticBlockType.Other =>
        s"Other block: ${messages.length} messages"
    }
}

object SemanticBlock {
  def startUserBlock(userMsg: UserMessage): SemanticBlock =
    SemanticBlock(
      id = SemanticBlockId.generate(),
      messages = Seq(userMsg),
      blockType = SemanticBlockType.UserAssistantPair,
      expectingAssistantResponse = true
    )

  def standaloneAssistant(msg: AssistantMessage): SemanticBlock =
    SemanticBlock(
      id = SemanticBlockId.generate(),
      messages = Seq(msg),
      blockType = SemanticBlockType.StandaloneAssistant
    )

  def standaloneSystem(msg: SystemMessage): SemanticBlock =
    SemanticBlock(
      id = SemanticBlockId.generate(),
      messages = Seq(msg),
      blockType = SemanticBlockType.StandaloneAssistant // Treat as assistant
    )

  def standaloneTool(msg: ToolMessage): SemanticBlock =
    SemanticBlock(
      id = SemanticBlockId.generate(),
      messages = Seq(msg),
      blockType = SemanticBlockType.StandaloneTool
    )

  def standaloneMessage(msg: Message): SemanticBlock =
    SemanticBlock(
      id = SemanticBlockId.generate(),
      messages = Seq(msg),
      blockType = SemanticBlockType.Other
    )
}

/**
 * Classification of semantic block types.
 *
 * Block types help compression algorithms make decisions about how to
 * handle different conversation patterns:
 *
 *  - '''UserAssistantPair''': Complete conversation turn, can be summarized
 *  - '''StandaloneAssistant''': Isolated response, preserve carefully
 *  - '''StandaloneTool''': Tool output without context, may need special handling
 *  - '''Other''': Unclassified, treat conservatively
 */
@Stable
sealed trait SemanticBlockType
object SemanticBlockType {
  case object UserAssistantPair   extends SemanticBlockType
  case object StandaloneAssistant extends SemanticBlockType
  case object StandaloneTool      extends SemanticBlockType
  case object Other               extends SemanticBlockType
}
