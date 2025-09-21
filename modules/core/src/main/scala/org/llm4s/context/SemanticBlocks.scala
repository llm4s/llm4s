package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.llm4s.types.{ Result, SemanticBlockId }
import org.slf4j.LoggerFactory

/**
 * Groups messages into semantic blocks (user+assistant pairs, tools, or standalone messages).
 *
 * Used by HistoryCompressor and other context management steps.
 */
object SemanticBlocks {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Group messages into semantic blocks (conversation pairs + standalone messages)
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
 * Represents a semantic block of related messages in a conversation
 */
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
 * Types of semantic blocks for different conversation patterns
 */
sealed trait SemanticBlockType
object SemanticBlockType {
  case object UserAssistantPair   extends SemanticBlockType
  case object StandaloneAssistant extends SemanticBlockType
  case object StandaloneTool      extends SemanticBlockType
  case object Other               extends SemanticBlockType
}
