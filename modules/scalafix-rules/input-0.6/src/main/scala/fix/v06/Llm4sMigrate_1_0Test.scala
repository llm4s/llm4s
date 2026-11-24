/*
rule = Llm4sMigrate_1_0
*/
package fix.v06

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort }

// The snippets of docs/reference/migration.md's notes since 0.6.0 that a rule rewrites, as 0.6.0 code.
object Llm4sMigrate_1_0Test {
  val options = CompletionOptions().withReasoning(ReasoningEffort.None)

  def warnOnLength(client: LLMClient, conversation: Conversation, warn: () => Unit) =
    client.streamComplete(conversation, options, chunk => chunk.finishReason.foreach(r => if (r == "length") warn()))

  def logStop(client: LLMClient, conversation: Conversation) =
    client.streamComplete(conversation, options, chunk => if (chunk.finishReason.contains("stop")) println("done"))

  def toolNames(tools: Seq[org.llm4s.toolapi.ToolFunction[_, _]]): Seq[String] = tools.map(_.name)
}
