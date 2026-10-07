package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ AssistantMessage, Message, SystemMessage, ToolMessage }

/**
 * Which tool calls and tool results of a conversation can go to a provider as native, paired
 * blocks, for providers that require a tool result to answer a call of the turn just before it
 * (Anthropic, Bedrock Converse).
 *
 * A call is paired when a [[ToolMessage]] with its id appears in the run of tool messages
 * immediately after the [[AssistantMessage]] that made it; that tool message is then paired too.
 * System messages do not break a run, since those providers lift them out of the message list.
 * Anything else - a user message between the call and its result, a result for a call of an
 * earlier turn, a second result for the same call - leaves the call unpaired: the client omits it
 * and sends the result as plain text, because the provider rejects a call without its result, and
 * a result that does not immediately follow its call or that follows text in the same user turn.
 *
 * @param calls   For each assistant message's index in the conversation, the ids of its paired calls.
 * @param results The indices of the paired tool messages.
 */
final private[llm4s] case class ToolResultPairing(calls: Map[Int, Set[String]], results: Set[Int]) {

  /** Whether the call `id` of the assistant message at `assistantIndex` is paired. */
  def callPaired(assistantIndex: Int, id: String): Boolean = calls.get(assistantIndex).exists(_.contains(id))

  /** Whether the tool message at `index` is paired. */
  def resultPaired(index: Int): Boolean = results.contains(index)

  /**
   * The assistant message at `index` as it may be sent: with only its paired calls, set through
   * [[AssistantMessage.withToolCalls]], so that dropping a call unseals the thinking (see
   * "Sealed thinking" on [[AssistantMessage]]) - a signed turn may go back only with the exact calls
   * it was returned with. With every call paired it is `message` itself, signatures intact.
   */
  def replayable(index: Int, message: AssistantMessage): AssistantMessage =
    message.withToolCalls(message.toolCalls.filter(tc => callPaired(index, tc.id)))
}

private[llm4s] object ToolResultPairing {

  def of(messages: Seq[Message]): ToolResultPairing = {
    val indexed = messages.toIndexedSeq
    val pairs = indexed.indices.flatMap { i =>
      indexed(i) match {
        case am: AssistantMessage if am.toolCalls.nonEmpty =>
          val ids = am.toolCalls.map(_.id).toSet
          // the run of tool messages straight after the call, system messages aside
          val run = indexed.zipWithIndex
            .drop(i + 1)
            .takeWhile {
              case (_: ToolMessage, _) | (_: SystemMessage, _) => true
              case _                                           => false
            }
            .collect { case (tm: ToolMessage, j) => j -> tm }
          // the first result for each of the turn's calls
          val paired = run
            .filter { case (_, tm) => ids.contains(tm.toolCallId) }
            .groupBy { case (_, tm) => tm.toolCallId }
            .values
            .map(_.minBy(_._1))
            .toSeq
          Seq(i -> paired)
        case _ => Seq.empty
      }
    }
    ToolResultPairing(
      calls = pairs.map { case (i, paired) => i -> paired.map(_._2.toolCallId).toSet }.toMap,
      results = pairs.flatMap(_._2.map(_._1)).toSet
    )
  }
}
