package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.{ StateUpdate, ThreadState }
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolMessage }
import org.llm4s.types.Result

/**
 * Closes a turn that stopped with tool calls unanswered (Stage 5 W2, design 4.56): every call of the conversation's
 * last assistant message that has no result in the run of tool messages after it gets a synthetic one, `content`, so
 * the history obeys [[ToolResultRule]] and the thread's next turn can call a model. A worker settling an entry whose
 * attempts ran out does this; W3's cancel of a queued run can do it with a "cancelled" result.
 */
private[agent] object SettledTurn:

  /**
   * The update giving each unanswered call of `state`'s last assistant message the result `content`, the messages'
   * ids under `prefix`; empty when the turn has no unanswered call (it ended on a user or assistant message).
   */
  def closing(state: ThreadState, prefix: String, content: String): Result[StateUpdate] =
    state.get(Messages.key).map { history =>
      val tail = history.reverseIterator.takeWhile(_.message.isInstanceOf[ToolMessage]).toVector
      history.lift(history.size - tail.size - 1).map(_.message) match
        case Some(assistant: AssistantMessage) if assistant.toolCalls.nonEmpty =>
          val answered = tail.collect { case StoredMessage(_, t: ToolMessage) => t.toolCallId }.toSet
          assistant.toolCalls
            .filterNot(call => answered(call.id))
            .foldLeft(StateUpdate.empty) { (update, call) =>
              update.update(
                Messages.key,
                MessageUpdate.Append(StoredMessage(s"$prefix/tool/${call.id}", ToolMessage(content, call.id)))
              )
            }
        case _ => StateUpdate.empty
    }
