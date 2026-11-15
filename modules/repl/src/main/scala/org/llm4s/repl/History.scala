package org.llm4s.repl

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ Conversation, Message }

import java.io.PrintStream

/**
 * The conversation a session has had so far, as `ask` and `chat()` recorded it: each question, each answer, and
 * the tool calls and results in between. A REPL prints it as a transcript, one message per line:
 *
 * {{{
 * user: What is the weather in Paris?
 * assistant: -> get_weather({"city":"Paris"})
 * tool: <- {"temperature":21}
 * assistant: It is 21 degrees in Paris.
 * }}}
 *
 * @param messages the messages, oldest first; the session's system prompt is not among them
 */
@Experimental
final class History private[repl] (val messages: Seq[Message], out: PrintStream) {

  /** The messages as a [[org.llm4s.llmconnect.model.Conversation]], to pass to an `LLMClient` or an agent. */
  def conversation: Conversation = Conversation(messages)

  /** The number of messages. */
  def size: Int = messages.size

  /** Prints the transcript to the session's output. */
  def show: Unit = out.println(toString)

  override def toString: String = Render.transcript(messages)
}
