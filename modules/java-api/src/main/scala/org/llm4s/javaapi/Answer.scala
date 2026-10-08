package org.llm4s.javaapi

import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.toolloop.ApprovalDecision
import org.llm4s.core.safety.Safety
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * An answer to one pending approval or question of a suspended turn, for [[JAgent.streamResume]]:
 * built with [[Answer.approve]], [[Answer.reject]], [[Answer.edit]] or [[Answer.reply]], with JSON as
 * a `String`.
 *
 * {{{
 * agent.streamResume(threadId, List.of(Answer.approve(id)), listener);
 * }}}
 *
 * A `null` argument or malformed JSON is reported when the answer is used: `streamResume` returns a
 * failed result.
 */
final class Answer private (val interruptId: String, encoded: () => Result[ujson.Value]) {

  /** The answer as `Agent.resume` takes it, or why it cannot be one. */
  private[javaapi] def underlying: Result[(InterruptId, ujson.Value)] =
    if (interruptId == null) Left(ValidationError.required("interruptId"))
    else encoded().map(InterruptId(interruptId) -> _)

  override def toString: String = s"Answer($interruptId)"
}

object Answer {

  /** Approves the pending tool call `interruptId`. */
  def approve(interruptId: String): Answer = decision(interruptId, Right(ApprovalDecision.Approve))

  /** Rejects the pending tool call `interruptId`: the model sees `Rejected: <reason>` as its result. */
  def reject(interruptId: String, reason: String): Answer =
    decision(
      interruptId,
      Option(reason).map(ApprovalDecision.Reject(_)).toRight(ValidationError.required("reason"))
    )

  /** Approves the pending tool call `interruptId` with new arguments, a JSON object as a `String`. */
  def edit(interruptId: String, argumentsJson: String): Answer =
    new Answer(interruptId, () => json("argumentsJson", argumentsJson).map(a => encode(ApprovalDecision.Edit(a))))

  /** Answers the tool question `interruptId` with `json`, the asking tool's answer type as JSON. */
  def reply(interruptId: String, json: String): Answer =
    new Answer(interruptId, () => Answer.json("json", json))

  private def decision(interruptId: String, decision: Result[ApprovalDecision]): Answer =
    new Answer(interruptId, () => decision.map(encode))

  private def encode(decision: ApprovalDecision): ujson.Value = upickle.default.writeJs(decision)

  private def json(field: String, text: String): Result[ujson.Value] =
    if (text == null) Left(ValidationError.required(field))
    else Safety.safely(ujson.read(text)).left.map(e => ValidationError(field, s"is not valid JSON: ${e.message}"))
}
