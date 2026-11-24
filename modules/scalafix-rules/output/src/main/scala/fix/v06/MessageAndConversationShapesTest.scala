package fix.v06

import org.llm4s.llmconnect.{ ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, Message, SystemMessage, ToolCall, UserMessage }
import org.llm4s.types.Result

object MessageAndConversationShapesTest {
  def text(message: AssistantMessage): String = message.text.getOrElse("(tool calls only)")

  def reply(text: String, calls: Seq[ToolCall]): AssistantMessage =
    AssistantMessage(text = Some(text).filter(_.nonEmpty), toolCalls = calls)

  def calling(calls: Seq[ToolCall]): AssistantMessage = AssistantMessage(text = None, toolCalls = calls)

  val blank = AssistantMessage(None)

  val none: Conversation = Conversation.empty

  def one(prompt: String): Result[Conversation] = Conversation.create(Seq(UserMessage(prompt)))

  def two(system: String, prompt: String): Result[Conversation] =
    Conversation.create(Seq(SystemMessage(system), UserMessage(prompt)))

  def spliced(messages: Seq[Message]): Result[Conversation] = Conversation.create(messages)

  def listed(messages: List[Message]): Result[Conversation] = Conversation.create(messages)

  def logging(sink: ProviderExchangeSink): ProviderExchangeLogging = ProviderExchangeLogging.Enabled(sink)
}
