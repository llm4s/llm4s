package org.llm4s.examples.http

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.error.ValidationError

import scala.util.Try

/**
 * The JSON of the two endpoints, shared by every reference app.
 *
 * Request (both endpoints): `{"message": "..."}`.
 *
 * `POST /chat` answers `{"threadId", "status", "answer"}`; `answer` is `null` unless `status` is `Completed`.
 * `POST /chat/stream` sends server-sent events: `delta` (`{"attempt", "text"}`, a piece of the answer), `tool_call`
 * (`{"id", "tool"}`), then one `done` (the `/chat` body) or one `error` (the error body with its `status`).
 */
object ChatProtocol {

  /** The `message` of a request body; a `ValidationError` for a body that is not JSON or has no non-blank message. */
  def message(body: String): Either[ValidationError, String] =
    Try(ujson.read(body)).toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("message"))
      .flatMap(_.strOpt) match {
      case Some(text) if text.trim.nonEmpty => Right(text)
      case Some(_)                          => Left(ValidationError("message", "must not be blank"))
      case None => Left(ValidationError("message", """the body must be a JSON object with a string "message""""))
    }

  /** The `/chat` response, and the `done` event's data. */
  def result(r: AgentResult): ujson.Obj =
    ujson.Obj(
      "threadId" -> r.threadId.value,
      "status"   -> statusName(r.status),
      "answer"   -> r.answer.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    )

  /** A `delta` event's data. */
  def delta(attempt: Int, text: String): ujson.Obj = ujson.Obj("attempt" -> attempt, "text" -> text)

  /** A `tool_call` event's data. */
  def toolCall(id: String, tool: String): ujson.Obj = ujson.Obj("id" -> id, "tool" -> tool)

  /** An `error` event's data: the error body of a non-streamed response, with the status it would have had. */
  def streamError(failure: HttpFailure): ujson.Obj = {
    val body = failure.body
    body("status") = failure.status
    failure.retryAfterSeconds.foreach(s => body("retryAfterSeconds") = ujson.Num(s.toDouble))
    body
  }

  private def statusName(status: AgentStatus): String = status.productPrefix
}
