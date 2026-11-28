package org.llm4s.knowledgegraph.query

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.types.Result

import java.time.Instant
import scala.collection.mutable

/** Replies with `replies` in order (the last one repeated), and records every conversation it is sent. */
final class ScriptedLLM(replies: String*) extends LLMClient {
  val conversations: mutable.ArrayBuffer[Conversation] = mutable.ArrayBuffer.empty

  /** The user prompt of each call, in order. */
  def prompts: Seq[String] =
    conversations.toSeq.map(_.messages.collect { case u: UserMessage => u.content }.mkString("\n"))

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    conversations += conversation
    val reply = replies.lift(conversations.size - 1).orElse(replies.lastOption).getOrElse("")
    Right(Completion("id", Instant.ofEpochSecond(1L), ModelName("m"), AssistantMessage(text = Some(reply)), None))
  }

  override def streamComplete(conversation: Conversation, options: CompletionOptions)(
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = complete(conversation, options)

  override def contextWindow: Int     = 8192
  override def reserveCompletion: Int = 1024
}
