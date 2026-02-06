package org.llm4s.testing.model

import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import upickle.default._

/**
 * Represents a recorded interaction with an LLM.
 *
 * @param conversation The input conversation history.
 * @param options      Configuration used for this request.
 * @param response     The response received from the LLM.
 */
case class Interaction(
  conversation: Conversation,
  options: CompletionOptions,
  response: Completion
)

object Interaction {
  implicit val rw: ReadWriter[Interaction] = macroRW
}
