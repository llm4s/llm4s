package org.llm4s.context

import org.llm4s.context.tokens.{ TokenizerMapping, Tokenizer }
import org.llm4s.error.TokenizerError
import org.llm4s.identity.TokenizerId
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Counts tokens in conversations and messages using configurable tokenizers.
 * Provides accurate token counting for context management and budget planning.
 */
class ConversationTokenCounter private (tokenizer: org.llm4s.context.tokens.StringTokenizer) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Count tokens in a single message
   */
  def countMessage(message: Message): Int =
    countMessageContent(message) + messageOverhead

  /**
   * Count total tokens in a conversation
   */
  def countConversation(conversation: Conversation): Int = {
    val totalTokens = conversation.messages.map(countMessage).sum + conversationOverhead
    logger.debug(s"Counted $totalTokens tokens in conversation with ${conversation.messages.length} messages")
    totalTokens
  }

  /**
   * Get detailed token breakdown for debugging
   */
  def getTokenBreakdown(conversation: Conversation): TokenBreakdown = {
    val messageInfos = conversation.messages.map(createMessageTokenInfo)
    TokenBreakdown(countConversation(conversation), messageInfos, conversationOverhead)
  }

  private def countMessageContent(message: Message): Int = message match {
    case msg: AssistantMessage => countAssistantMessage(msg)
    case msg: ToolMessage      => countToolMessage(msg)
    case msg                   => countTextContent(msg.content)
  }

  private def countAssistantMessage(message: AssistantMessage): Int = {
    val contentTokens  = countTextContent(message.content)
    val toolCallTokens = message.toolCalls.map(countToolCall).sum
    contentTokens + toolCallTokens
  }

  private def countToolMessage(message: ToolMessage): Int =
    countTextContent(message.content) + countTextContent(s"tool_call_id:${message.toolCallId}")

  private def countTextContent(text: String): Int =
    tokenizer.encode(text).length

  private def countToolCall(toolCall: ToolCall): Int = {
    val nameTokens = countTextContent(toolCall.name)
    val argsTokens = countTextContent(toolCall.arguments.render())
    nameTokens + argsTokens + toolCallOverhead
  }

  private def createMessageTokenInfo(message: Message): MessageTokenInfo =
    MessageTokenInfo(
      role = message.role.name,
      tokens = countMessage(message),
      preview = message.content.take(50)
    )

  private val messageOverhead      = 4
  private val toolCallOverhead     = 10
  private val conversationOverhead = 10
}

object ConversationTokenCounter {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a token counter for a specific tokenizer
   */
  def apply(tokenizerId: TokenizerId): Result[ConversationTokenCounter] =
    createCounter(tokenizerId)

  /**
   * Create a token counter using the most common OpenAI tokenizer (cl100k_base)
   */
  def openAI(): Result[ConversationTokenCounter] =
    apply(TokenizerId.CL100K_BASE)

  /**
   * Create a token counter using the newer OpenAI tokenizer (o200k_base) for GPT-4o
   */
  def openAI_o200k(): Result[ConversationTokenCounter] =
    apply(TokenizerId.O200K_BASE)

  /**
   * Create a token counter for a specific model name (provider-aware)
   * This is the recommended way to create token counters as it automatically
   * selects the appropriate tokenizer for the model.
   */
  def forModel(modelName: String): Result[ConversationTokenCounter] = {
    val tokenizerId  = TokenizerMapping.getTokenizerId(modelName)
    val accuracyInfo = TokenizerMapping.getAccuracyInfo(modelName)

    logger.info(s"Creating token counter for model '$modelName' using $tokenizerId")
    logger.debug(s"Tokenizer accuracy: ${accuracyInfo.description}")

    apply(tokenizerId)
  }

  private def createCounter(tokenizerId: TokenizerId): Result[ConversationTokenCounter] =
    Tokenizer.lookupStringTokenizer(tokenizerId) match {
      case Some(tokenizer) =>
        logger.debug(s"Successfully created tokenizer for $tokenizerId")
        Right(new ConversationTokenCounter(tokenizer))
      case None =>
        logger.error(s"Tokenizer not available for $tokenizerId")
        Left(TokenizerError.notAvailable(tokenizerId.name))
    }
}

/**
 * Detailed breakdown of token usage in a conversation
 */
case class TokenBreakdown(
  totalTokens: Int,
  messages: Seq[MessageTokenInfo],
  overhead: Int
) {
  def prettyPrint(): String = {
    val header       = s"=== Token Breakdown (Total: $totalTokens) ===\n"
    val messageLines = messages.zipWithIndex.map(formatMessageLine).mkString
    val overheadLine = f"Overhead: $overhead%3d tokens\n"
    header + messageLines + overheadLine
  }

  private def formatMessageLine(msgWithIndex: (MessageTokenInfo, Int)): String = {
    val (msgInfo, index) = msgWithIndex
    f"${index + 1}%2d. [${msgInfo.role}%9s] ${msgInfo.tokens}%3d tokens: ${msgInfo.preview}...\n"
  }
}

/**
 * Token information for a single message
 */
case class MessageTokenInfo(
  role: String,
  tokens: Int,
  preview: String
)
