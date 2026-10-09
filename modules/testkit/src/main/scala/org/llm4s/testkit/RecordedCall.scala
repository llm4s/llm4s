package org.llm4s.testkit

import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, Message, UserMessage }

/**
 * One request a [[ScriptedLLMClient]] received, kept so a test can check what the code under test sent.
 *
 * @param conversation the conversation the code sent
 * @param options the completion options the code sent
 * @param streamed true when the request came through `streamComplete`
 */
final case class RecordedCall private (
  conversation: Conversation,
  options: CompletionOptions,
  streamed: Boolean
) {

  /** The messages of the conversation. */
  def messages: Seq[Message] = conversation.messages

  /** The text of the last user message, if the conversation has one. */
  def lastUserText: Option[String] =
    conversation.messages.reverseIterator.collectFirst { case m: UserMessage => m.content }
}

object RecordedCall {

  /** Creates a [[RecordedCall]]. */
  def apply(conversation: Conversation, options: CompletionOptions, streamed: Boolean = false): RecordedCall =
    new RecordedCall(conversation, options, streamed)
}
