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
 *
 * Token counting is essential for:
 *  - Ensuring conversations fit within model context windows
 *  - Budget planning for API costs (many providers charge per token)
 *  - Context compression decisions in [[ContextManager]]
 *
 * The counter applies fixed overheads to account for special tokens:
 *  - Message overhead: 4 tokens per message (role markers, delimiters)
 *  - Tool call overhead: 10 tokens per tool call (function markers)
 *  - Conversation overhead: 10 tokens (conversation framing)
 *
 * @example
 * {{{
 * val counter = ConversationTokenCounter.forModel("gpt-4o").getOrElse(???)
 * val tokens = counter.countConversation(conversation)
 * println(s"Conversation uses \$tokens tokens")
 * }}}
 *
 * @see [[ConversationTokenCounter.forModel]] for model-aware counter creation
 * @see [[TokenBreakdown]] for detailed per-message token analysis
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

/**
 * Factory methods for creating [[ConversationTokenCounter]] instances.
 *
 * Provides model-aware counter creation that automatically selects the appropriate
 * tokenizer based on the model name. Supports OpenAI, Anthropic, Azure, and Ollama models.
 *
 * ==Tokenizer Selection==
 *
 * Different models use different tokenization schemes:
 *  - '''GPT-4o, o1''': Uses `o200k_base` tokenizer
 *  - '''GPT-4, GPT-3.5''': Uses `cl100k_base` tokenizer
 *  - '''Claude models''': Uses `cl100k_base` approximation (may differ 20-30%)
 *  - '''Ollama models''': Uses `cl100k_base` approximation
 *
 * @example
 * {{{
 * // Model-aware creation (recommended)
 * val counter = ConversationTokenCounter.forModel("openai/gpt-4o")
 *
 * // Direct tokenizer selection
 * val openAICounter = ConversationTokenCounter.openAI()
 * val gpt4oCounter = ConversationTokenCounter.openAI_o200k()
 * }}}
 */
object ConversationTokenCounter {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Test-only factory to avoid reflection in tests.
   */
  private[context] def forTest(
    tokenizer: org.llm4s.context.tokens.StringTokenizer
  ): ConversationTokenCounter =
    new ConversationTokenCounter(tokenizer)

  /**
   * Create a token counter for a specific tokenizer.
   *
   * @param tokenizerId The tokenizer to use (e.g., `TokenizerId.CL100K_BASE`)
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   */
  def apply(tokenizerId: TokenizerId): Result[ConversationTokenCounter] =
    createCounter(tokenizerId)

  /**
   * Create a token counter using the OpenAI `cl100k_base` tokenizer.
   *
   * Suitable for GPT-4, GPT-3.5-turbo, and most embedding models.
   * This is the most common OpenAI tokenizer and a reasonable fallback for unknown models.
   *
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   */
  def openAI(): Result[ConversationTokenCounter] =
    apply(TokenizerId.CL100K_BASE)

  /**
   * Create a token counter using the OpenAI `o200k_base` tokenizer.
   *
   * Suitable for GPT-4o and o1 series models which use this newer tokenizer
   * with a larger vocabulary (200k tokens vs 100k).
   *
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   */
  def openAI_o200k(): Result[ConversationTokenCounter] =
    apply(TokenizerId.O200K_BASE)

  /**
   * Create a token counter for a specific model name with automatic tokenizer selection.
   *
   * This is the '''recommended''' way to create token counters as it automatically
   * selects the appropriate tokenizer based on the model name and provider.
   *
   * The model name should be in the format `provider/model-name` (e.g., `openai/gpt-4o`,
   * `anthropic/claude-3-sonnet`). Plain model names are also supported.
   *
   * @param modelName The model identifier (e.g., "gpt-4o", "openai/gpt-4o", "claude-3-sonnet")
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   * @see [[tokens.TokenizerMapping]] for the full model-to-tokenizer mapping
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
