package org.llm4s.agent.testkit

import org.llm4s.agent.Agent
import org.llm4s.agent.delegation.TypedAgent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.time.Instant

/** A delegating agent for the effect modules' nested-stream specs (design 4.47). */
object NestedFixtures {

  def completion(text: String, calls: Seq[ToolCall] = Nil): Completion = {
    val message = if (calls.isEmpty) AssistantMessage(text, Seq.empty) else AssistantMessage(None, calls)
    Completion("n", Instant.EPOCH, message.content, "m", message, calls.toList, Some(TokenUsage(1, 1, 2)))
  }

  /**
   * The lead delegates once to a streaming text subagent `general`, which answers "hello" in chunks of three; the
   * lead's first call waits for `subscribed`, so a subscription made after `start` sees the whole delegation.
   */
  def delegating(subscribed: CountDownLatch): Agent = {
    val client = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        val messages = conversation.messages.toVector
        val prompt   = messages.headOption.map(_.content).getOrElse("")
        val answered = messages.exists { case _: ToolMessage => true; case _ => false }
        if (prompt.startsWith("CHILD")) Right(completion("hello"))
        else if (answered) Right(completion("composed"))
        else {
          subscribed.await(10, TimeUnit.SECONDS): Unit
          Right(
            completion(
              "",
              Seq(ToolCall("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "say hello")))
            )
          )
        }
      }
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] =
        complete(conversation, options).map { c =>
          c.content.grouped(3).foreach(piece => onChunk(StreamedChunk("c", Some(piece))))
          c
        }
      def getContextWindow(): Int     = 128000
      def getReserveCompletion(): Int = 4096
    }
    val general = TypedAgent.text("general", "Says hello").withSystemPrompt("CHILD").withStreaming()
    Agent
      .builder("lead", client)
      .withSystemPrompt("LEAD")
      .withSubagents(general)
      .build()
      .fold(e => throw new IllegalStateException(e.message), identity)
  }
}
