package org.llm4s.testkit

import org.llm4s.error.{ LLMError, NonRecoverableError }

/**
 * The error a [[ScriptedLLMClient]] returns for a call its script has no reply for: a call past the end of a
 * [[ScriptedLLMClient.sequence]], or a conversation no case of [[ScriptedLLMClient.respondingTo]] matches.
 *
 * It is a type of its own, so a test can tell "the code made a call I did not script" apart from every error a
 * real provider returns, and from the errors a test scripts with [[Reply.failure]]:
 *
 * {{{
 * client.complete(conversation, options) match {
 *   case Left(e: UnscriptedCallError) => e.callNumber   // 3
 *   case other                        => ...
 * }
 * }}}
 *
 * It is a `NonRecoverableError`, so a `ReliableClient` around the scripted client does not retry it.
 *
 * @param callNumber which call had no reply, counting from 1
 * @param reason why there was none, for example `the script has 2 replies`
 * @param lastMessage the last message of the conversation, as `role: "text"` cut to 80 characters; `None` when the
 *                    conversation had no messages
 */
final case class UnscriptedCallError private (
  callNumber: Int,
  reason: String,
  lastMessage: Option[String]
) extends LLMError
    with NonRecoverableError {

  override def message: String = {
    val last = lastMessage.fold("the conversation has no messages")(m => s"the last message was $m")
    s"no reply is scripted for call $callNumber: $reason; $last"
  }

  override def context: Map[String, String] = Map("callNumber" -> callNumber.toString)
}

object UnscriptedCallError {

  /** Creates an [[UnscriptedCallError]]; [[ScriptedLLMClient]] is what builds one in a test. */
  def apply(callNumber: Int, reason: String, lastMessage: Option[String]): UnscriptedCallError =
    new UnscriptedCallError(callNumber, reason, lastMessage)
}
