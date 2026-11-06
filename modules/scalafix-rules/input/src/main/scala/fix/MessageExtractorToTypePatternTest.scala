/*
rule = MessageExtractorToTypePattern
*/
package fix

import org.llm4s.llmconnect.model.{ Message, ToolMessage, UserMessage }

object MessageExtractorToTypePatternTest {
  def describe(message: Message): String = message match {
    case UserMessage(content) if content.nonEmpty => s"user: $content"
    case ToolMessage(content, id)                 => s"tool $id: ${content.length}"
    case m @ UserMessage(text) if text.nonEmpty   => text
    case UserMessage(_)                           => "empty"
    case other                                    => other.content
  }
}
