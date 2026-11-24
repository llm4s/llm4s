/*
rule = CompletionFromMessage
*/
package fix.v06

import org.llm4s.llmconnect.model.{ Completion, ToolCall }

// What CompletionFromMessage reports instead of rewriting: the two setters that are gone, and a pattern that binds one
// of the two fields the extractor lost.
object CompletionFromMessageLintTest {
  def retitled(completion: Completion): Completion = completion.withContent("other") // assert: CompletionFromMessage.setterRemoved

  def without(completion: Completion): Completion = completion.withToolCalls(List.empty[ToolCall]) // assert: CompletionFromMessage.setterRemoved

  def summary(completion: Completion): String = completion match {
    case Completion(id, _, content, _, _, _, _, _, _, _) => s"$id $content" // assert: CompletionFromMessage.patternBindsRemovedField
  }
}
