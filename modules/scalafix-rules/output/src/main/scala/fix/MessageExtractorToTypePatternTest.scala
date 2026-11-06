package fix

import org.llm4s.llmconnect.model.{ Message, ToolMessage, UserMessage }

object MessageExtractorToTypePatternTest {
  def describe(message: Message): String = message match {
    case userMessage: UserMessage if userMessage.content.nonEmpty => s"user: ${userMessage.content}"
    case toolMessage: ToolMessage                 => s"tool ${toolMessage.toolCallId}: ${toolMessage.content.length}"
    case m: UserMessage if m.content.nonEmpty   => m.content
    case _: UserMessage                           => "empty"
    case other                                    => other.content
  }
}

