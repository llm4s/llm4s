package org.llm4s.javaapi

import org.llm4s.agent.ContextWindowConfig
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * How much of a conversation an agent sends the model on each call, for [[JAgentBuilder.contextWindow]] (#2476): the
 * Java form of `ContextWindowMiddleware`. It prunes only what is sent; the conversation keeps every message, so
 * `JAgentResult.messages()` is whole.
 *
 * {{{
 * Llm4s.agentBuilder("support", client).contextWindow(JContextWindow.lastMessages(20))
 * }}}
 */
final class JContextWindow private[javaapi] (
  token: FacadeToken,
  private[javaapi] val config: Result[ContextWindowConfig]
) {

  FacadeToken.check(token, "JContextWindow")

  override def toString: String =
    config.fold(
      e => s"JContextWindow(invalid: ${e.message})",
      c => s"JContextWindow(lastMessages=${c.maxMessages.mkString})"
    )
}

object JContextWindow {

  /**
   * Sends the model at most `messages` messages of the conversation, the most recent, dropping the oldest first. The
   * system prompt is always sent first and is not counted. The current turn - from the latest query on - is always sent
   * whole, so a call may exceed the limit when the turn alone is longer; a tool call is never sent without its result,
   * nor a result without its call; and what is sent starts with a query.
   *
   * Does not throw: `messages` below 1 gives a window that the agent's `build()` refuses with a failed result.
   */
  def lastMessages(messages: Int): JContextWindow =
    new JContextWindow(
      FacadeToken.INSTANCE,
      if (messages < 1) Left(ValidationError.invalid("messages", s"must be at least 1, was $messages"))
      else Right(ContextWindowConfig(maxMessages = Some(messages)))
    )
}
