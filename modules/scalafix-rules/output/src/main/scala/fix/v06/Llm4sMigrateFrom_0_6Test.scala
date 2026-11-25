package fix.v06

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort }
import org.llm4s.llmconnect.model.{ FinishReason, Thinking }

// The snippets of docs/reference/migration.md's notes since 0.6.0 that a rule rewrites, as 0.6.0 code.
object Llm4sMigrateFrom_0_6Test {
  val options = CompletionOptions().withThinking(Thinking.Off)

  def warnOnLength(client: LLMClient, conversation: Conversation, warn: () => Unit) =
    client.streamComplete(conversation, options)(chunk => chunk.finishReason.map(_.name).foreach(r => if (r == "length") warn()))

  def logStop(client: LLMClient, conversation: Conversation) =
    client.streamComplete(conversation, options)(chunk => if (chunk.finishReason.contains(FinishReason.Stop)) println("done"))

  def toolNames(tools: Seq[org.llm4s.toolapi.ToolFunction[_]]): Seq[String] = tools.map(_.name)

  def promptBudget(client: LLMClient): Int = client.contextWindow - client.reserveCompletion
}
