package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

/** The streamed reply, as the caller saw it arrive and as the client returned it at the end. */
final case class Streamed(pieces: Vector[String], text: String)

/** Recipe: print a reply to the terminal as it is generated, instead of waiting for all of it. */
object StreamingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "streaming",
    title = "Stream tokens to the terminal",
    summary = "Print each piece of the reply as the model produces it, and get the whole completion at the end.",
    mainClass = "org.llm4s.samples.cookbook.StreamingRecipe"
  )

  def stream(client: LLMClient, question: String, onText: String => Unit): Result[Streamed] = {
    val conversation = Conversation(Seq(SystemMessage("Answer in two or three sentences."), UserMessage(question)))
    val pieces       = Vector.newBuilder[String]
    client
      .streamComplete(
        conversation,
        CompletionOptions().withMaxTokens(200),
        chunk =>
          // A chunk can carry a tool call, a finish reason or reasoning instead of text: print only the text.
          chunk.content.filter(_.nonEmpty).foreach { text =>
            pieces += text
            onText(text)
          }
      )
      .map(completion => Streamed(pieces.result(), completion.content))
  }

  /** Writes each piece as it arrives; `flush`, because `print` alone may hold the text back until a newline. */
  def toTerminal(text: String): Unit = {
    print(text)
    Console.flush()
  }

  /** Streams a fixed reply a word at a time, as [[ScriptedClient]] streams every reply. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(
      AssistantMessage(
        "Streaming sends the reply in pieces while the model is still writing it. " +
          "The user sees the first words at once instead of waiting for the whole answer."
      )
    )
  )

  def demo(client: LLMClient): Result[String] =
    stream(client, "Why stream a model's reply?", toTerminal).map { streamed =>
      s"\n(${streamed.pieces.size} pieces, ${streamed.text.length} characters)"
    }
}
