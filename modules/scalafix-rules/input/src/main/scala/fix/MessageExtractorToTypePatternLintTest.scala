/*
rule = MessageExtractorToTypePattern
*/
package fix

import org.llm4s.llmconnect.model.{ Message, UserMessage }

// What MessageExtractorToTypePattern reports instead of rewriting: a nested extractor, and a literal argument.
object MessageExtractorToTypePatternLintTest {
  def first(messages: List[Message]): String = messages match {
    case UserMessage(text) :: _ => text // assert: MessageExtractorToTypePattern.messageExtractor
    case _                      => ""
  }

  def isGreeting(message: Message): Boolean = message match {
    case UserMessage("hello") => true // assert: MessageExtractorToTypePattern.messageExtractor
    case _                    => false
  }
}
