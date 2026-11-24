package org.llm4s.context

import org.llm4s.annotation.Stable
import org.llm4s.context.tokens.{ StringTokenizer, Tokenizer, TokenizerId }
import org.llm4s.error.TokenizerError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Counts tokens in conversations and messages with a [[tokens.StringTokenizer]]: one of the bundled
 * jtokkit vocabularies, or your own through [[ConversationTokenCounter.of]].
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
 * val result = for {
 *   counter <- ConversationTokenCounter.o200k()
 * } yield {
 *   counter.countConversation(conversation)
 * }
 *
 * result match {
 *   case Right(tokens) =>
 *     println(s"Conversation uses $$tokens tokens")
 *   case Left(error) =>
 *     println(s"Error: $${error.message}")
 * }
 * }}}
 *
 * @see [[ConversationTokenCounter.create]] for a counter over a bundled vocabulary
 * @see [[TokenBreakdown]] for detailed per-message token analysis
 */
@Stable
final class ConversationTokenCounter private (tokenizer: StringTokenizer) {
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
   * A per-message token breakdown, for debugging
   */
  def getTokenBreakdown(conversation: Conversation): TokenBreakdown = {
    val messageInfos = conversation.messages.map(createMessageTokenInfo)
    TokenBreakdown(countConversation(conversation), messageInfos, conversationOverhead)
  }

  private def countMessageContent(message: Message): Int = message match {
    case msg: AssistantMessage => countAssistantMessage(msg)
    case msg: ToolMessage      => countToolMessage(msg) + mediaTokens(msg.parts)
    case msg: UserMessage      => countTextContent(msg.content) + mediaTokens(msg.parts)
    case msg                   => countTextContent(msg.content)
  }

  // media is not in the text view: estimated, so images and documents count against the window
  private def mediaTokens(parts: Vector[ContentPart]): Int = parts.map(ContentPart.estimatedTokens).sum

  // Thinking counts too: Anthropic, Bedrock, Ollama, DeepSeek, Z.ai, OpenRouter and Mistral send it
  // back, and a short answer can carry thousands of reasoning tokens. Providers that drop it
  // (OpenAI, Gemini) are over-counted, which only trims early; under-counting overflows the window.
  private def countAssistantMessage(message: AssistantMessage): Int = {
    val contentTokens  = countTextContent(message.content)
    val toolCallTokens = message.toolCalls.map(countToolCall).sum
    contentTokens + toolCallTokens + countThinking(message.thinking)
  }

  private def countThinking(thinking: Seq[ThinkingBlock]): Int =
    thinking.map {
      case ThinkingBlock.Text(text, _) => countTextContent(text)
      // opaque encrypted data, not text the tokenizer can measure: estimate from its size
      case ThinkingBlock.Redacted(data)  => ConversationTokenCounter.estimateOpaqueTokens(data)
      case ThinkingBlock.Opaque(_, data) => ConversationTokenCounter.estimateOpaqueTokens(data)
    }.sum

  private def countToolMessage(message: ToolMessage): Int =
    countTextContent(message.content) + countTextContent(s"tool_call_id:${message.toolCallId}")

  private def countTextContent(text: String): Int =
    tokenizer.count(text)

  private def countToolCall(toolCall: ToolCall): Int = {
    val nameTokens = countTextContent(toolCall.name)
    val argsTokens = countTextContent(toolCall.arguments.render())
    nameTokens + argsTokens + toolCallOverhead
  }

  private def createMessageTokenInfo(message: Message): MessageTokenInfo =
    MessageTokenInfo(
      role = message.role,
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
 * A counter is built over a tokenizer, never over a model name: which vocabulary a model uses is its
 * vendor's knowledge, so the mapping lives in the vendor's module (`OpenAITokenizers.counterFor` in
 * `llm4s-openai`). For a model with no public tokenizer, `cl100k()` is the usual approximation; counts
 * can differ from the provider's by 20-30%.
 *
 * @example
 * {{{
 * val approximate = ConversationTokenCounter.cl100k()
 * val larger      = ConversationTokenCounter.o200k()
 * val own         = ConversationTokenCounter.of(myTokenizer)
 * }}}
 */
object ConversationTokenCounter {
  private val logger = LoggerFactory.getLogger(getClass)

  /** A token estimate for opaque data (redacted thinking): one token per four characters, rounded up. */
  private[llm4s] def estimateOpaqueTokens(data: String): Int = (data.length + 3) / 4

  /**
   * A counter over the given tokenizer: one of the bundled ones
   * ([[tokens.Tokenizer.lookupStringTokenizer]]) or your own implementation of
   * [[tokens.StringTokenizer]].
   */
  def of(tokenizer: StringTokenizer): ConversationTokenCounter =
    new ConversationTokenCounter(tokenizer)

  /**
   * Create a token counter for a bundled tokenizer vocabulary.
   *
   * @param tokenizerId The tokenizer to use (e.g., `TokenizerId.CL100K_BASE`)
   * @return A Result containing the counter, or a `TokenizerError` if the vocabulary is not bundled
   */
  def create(tokenizerId: TokenizerId): Result[ConversationTokenCounter] =
    Tokenizer.lookupStringTokenizer(tokenizerId) match {
      case Some(tokenizer) =>
        logger.debug(s"Successfully created tokenizer for $tokenizerId")
        Right(new ConversationTokenCounter(tokenizer))
      case None =>
        logger.error(s"Tokenizer not available for $tokenizerId")
        Left(TokenizerError.notAvailable(tokenizerId.name))
    }

  /**
   * Create a token counter using the `cl100k_base` vocabulary: the common approximation for a model
   * whose own tokenizer is not public.
   *
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   */
  def cl100k(): Result[ConversationTokenCounter] =
    create(TokenizerId.CL100K_BASE)

  /**
   * Create a token counter using the `o200k_base` vocabulary, which has a larger vocabulary
   * (200k tokens against 100k).
   *
   * @return A Result containing the counter, or an error if the tokenizer is unavailable
   */
  def o200k(): Result[ConversationTokenCounter] =
    create(TokenizerId.O200K_BASE)
}

/**
 * Detailed breakdown of token usage in a conversation
 */
@Stable
final case class TokenBreakdown private (
  totalTokens: Int,
  messages: Seq[MessageTokenInfo],
  overhead: Int
) {

  /** Sets the total token count. */
  def withTotalTokens(totalTokens: Int): TokenBreakdown = copy(totalTokens = totalTokens)

  /** Sets the per-message token information. */
  def withMessages(messages: Seq[MessageTokenInfo]): TokenBreakdown = copy(messages = messages)

  /** Sets the conversation overhead in tokens. */
  def withOverhead(overhead: Int): TokenBreakdown = copy(overhead = overhead)

  def prettyPrint(): String = {
    val header       = s"=== Token Breakdown (Total: $totalTokens) ===\n"
    val messageLines = messages.zipWithIndex.map(formatMessageLine).mkString
    val overheadLine = f"Overhead: $overhead%3d tokens\n"
    header + messageLines + overheadLine
  }

  private def formatMessageLine(msgWithIndex: (MessageTokenInfo, Int)): String = {
    val (msgInfo, index) = msgWithIndex
    f"${index + 1}%2d. [${msgInfo.role.name}%9s] ${msgInfo.tokens}%3d tokens: ${msgInfo.preview}...\n"
  }
}

object TokenBreakdown {

  /** Creates a [[TokenBreakdown]]. */
  def apply(totalTokens: Int, messages: Seq[MessageTokenInfo], overhead: Int): TokenBreakdown =
    new TokenBreakdown(totalTokens, messages, overhead)
}

/**
 * Token information for a single message
 */
@Stable
final case class MessageTokenInfo private (
  role: MessageRole,
  tokens: Int,
  preview: String
) {

  /** Sets the message role. */
  def withRole(role: MessageRole): MessageTokenInfo = copy(role = role)

  /** Sets the message's token count. */
  def withTokens(tokens: Int): MessageTokenInfo = copy(tokens = tokens)

  /** Sets the content preview. */
  def withPreview(preview: String): MessageTokenInfo = copy(preview = preview)
}

object MessageTokenInfo {

  /** Creates a [[MessageTokenInfo]]. */
  def apply(role: MessageRole, tokens: Int, preview: String): MessageTokenInfo =
    new MessageTokenInfo(role, tokens, preview)
}
