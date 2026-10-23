package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.{ StateUpdate, ThreadState }
import org.llm4s.llmconnect.model.{ AssistantMessage, ContentPart, ToolMessage }
import org.llm4s.types.Result

/**
 * Closes a turn that stopped with tool calls unanswered (Stage 5 W2 and W3, designs 4.56 and 4.57): every call of the
 * conversation's last assistant message that has no result in the run of tool messages after it gets one, so the
 * history obeys [[ToolResultRule]] and the thread's next turn can call a model. A call whose tool ran to its end and
 * recorded a result in the current batch ([[ToolLoop.results]]) that was not yet collected - the run stopped at the
 * superstep boundary after it, as an interrupt stops it - keeps that result, as the batch's collection would have
 * appended it; every other call gets the synthetic `content`. The batch's recorded results and its lock schedule
 * ([[ToolLoop.queue]]) are cleared, so the next turn's batch starts empty. A worker settling an entry whose attempts ran
 * out does this, and a cancelled or interrupted queued run as it stops.
 */
private[agent] object SettledTurn:

  /**
   * The update closing `state`'s last turn: each unanswered call of its last assistant message gets its recorded
   * result, else `content`, the messages' ids under `prefix`; empty when the turn has no unanswered call (it ended on a
   * user or assistant message) and no batch is pending.
   */
  def closing(state: ThreadState, prefix: String, content: String): Result[StateUpdate] =
    for
      history  <- state.get(Messages.key)
      recorded <- if state.isSet(ToolLoop.results) then state.get(ToolLoop.results) else Right(Vector.empty)
    yield
      val tail = history.reverseIterator.takeWhile(_.message.isInstanceOf[ToolMessage]).toVector
      val closed = history.lift(history.size - tail.size - 1) match
        case Some(StoredMessage(sourceId, assistant: AssistantMessage)) if assistant.toolCalls.nonEmpty =>
          val answered = tail.collect { case StoredMessage(_, t: ToolMessage) => t.toolCallId }.toSet
          assistant.toolCalls
            .filterNot(call => answered(call.id))
            .foldLeft(StateUpdate.empty) { (update, call) =>
              val message = recorded.find(r => r.assistantMessageId == sourceId && r.toolCallId == call.id) match
                // as the batch's collection appends it
                case Some(r) if r.isError =>
                  ToolMessage(
                    Vector(ContentPart.Text(ujson.Obj("error" -> ContentPart.text(r.parts)).render())),
                    call.id
                  )
                case Some(r) => ToolMessage(r.parts, call.id)
                case None    => ToolMessage(content, call.id)
              update.update(Messages.key, MessageUpdate.Append(StoredMessage(s"$prefix/tool/${call.id}", message)))
            }
        case _ => StateUpdate.empty
      val withoutResults = if recorded.nonEmpty then closed.remove(ToolLoop.results) else closed
      if state.isSet(ToolLoop.queue) then withoutResults.remove(ToolLoop.queue) else withoutResults
