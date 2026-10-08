package org.llm4s.samples.cookbook

import org.llm4s.agent.memory.{ MemoryManager, SimpleMemoryManager }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage, UserMessage }
import org.llm4s.types.Result

/** What the model said, and the remembered context it was shown. */
final case class Remembered(reply: String, context: String)

/**
 * Recipe: remember what the user told you, and use it on a later turn.
 *
 * Facts are recorded in a memory manager, and when the next question arrives the relevant ones are retrieved and put
 * in the system prompt. The in-memory store matches words as substrings of the memory text, so a short word such as
 * "I" or "or" matches almost anything, and "Java?" with its question mark matches nothing. The recipe therefore
 * searches with the question's content words only. The question still has to share a word with the fact:
 * "Which language do I prefer, Scala or Java?" finds "Prefers Scala over Java", but "What do I like?" would not.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MemoryRecipe --live"   # your configured provider
 * }}}
 */
object MemoryRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "memory",
    title = "Remember facts between turns",
    summary = "Record what a user says, then retrieve it into the prompt when it matters.",
    mainClass = "org.llm4s.samples.cookbook.MemoryRecipe"
  )

  val facts: Seq[String] = Seq("Prefers Scala over Java", "Works in the Berlin office")

  val question: String = "Which language do I prefer, Scala or Java?"

  // snippet:start
  /** The store matches words as substrings, so give it the content words, without punctuation or short words. */
  def contentWords(question: String): String =
    question.toLowerCase.split("[^a-z0-9]+").filter(_.length > 3).distinct.mkString(" ")

  def recall(client: LLMClient, facts: Seq[String], question: String): Result[Remembered] =
    for {
      manager <- facts.foldLeft[Result[MemoryManager]](Right(SimpleMemoryManager.empty)) { (manager, fact) =>
        manager.flatMap(_.recordUserFact(fact, Some("user-1"), Some(0.9)))
      }
      context <- manager.getRelevantContext(contentWords(question))
      reply <- client.complete(
        Conversation(Seq(SystemMessage(s"What you know about the user:\n$context"), UserMessage(question)))
      )
    } yield Remembered(reply.content, context)
  // snippet:end

  /** Answers from the system prompt when it contains the fact, and admits it does not know otherwise. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val system = conversation.messages.find(_.role == MessageRole.System).map(_.content).getOrElse("")
    Right(AssistantMessage(if (system.contains("Prefers Scala")) "You prefer Scala." else "I do not know yet."))
  })

  def demo(client: LLMClient): Result[String] =
    recall(client, facts, question).map(r =>
      s"${r.reply}\n(context shown to the model: ${r.context.replace('\n', ' ')})"
    )
}
