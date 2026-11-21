package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ContentFilteredError, ValidationError }
import org.llm4s.llmconnect.model.Completion

/**
 * How a Gemini or Vertex AI reply that did not finish normally ends, the same way for `complete` and for a stream:
 *
 *  - A content-filter `finishReason` ([[FilterFinishes]]) is a [[org.llm4s.error.ContentFilteredError]] whether or
 *    not text came before it: a reply the filter cut short is not a finished answer. A stream's text already
 *    delivered stays delivered; the error says how much text the filter cut after. So is a
 *    `promptFeedback.blockReason` with no candidate: the prompt itself was blocked.
 *  - Any other `finishReason` but `STOP`, `MAX_TOKENS` or `CONTINUATION` (`MALFORMED_FUNCTION_CALL`, `OTHER`, ...)
 *    is a `ValidationError` naming it when the whole reply has no text and no tool call; otherwise the reply is
 *    returned with what was produced. A stream decides this at its end, from what it accumulated.
 *  - `STOP`, `MAX_TOKENS` or `CONTINUATION` with nothing produced (a thinking model that spent its whole budget
 *    thinking) is an empty answer.
 */
private[provider] object GeminiBlocked {

  /** The finishes of a reply that ended as asked: whatever it produced is the answer. */
  private val AnswerFinishes = Set("STOP", "MAX_TOKENS", "CONTINUATION")

  /**
   * The finish reasons that mean a content filter stopped the reply. From the Gemini API's `Candidate.finishReason`
   * (https://ai.google.dev/api/generate-content, read 2026-11-20): the reasons it documents as content flagged,
   * filtered or stopped for forbidden, prohibited, sensitive or recited content.
   */
  private val FilterFinishes = Set(
    "SAFETY",
    "RECITATION",
    "LANGUAGE",
    "BLOCKLIST",
    "PROHIBITED_CONTENT",
    "SPII",
    "IMAGE_SAFETY",
    "IMAGE_PROHIBITED_CONTENT",
    "IMAGE_RECITATION",
    "ESCALATION"
  )

  private def candidate(json: ujson.Value): Option[ujson.Value] =
    json.objOpt
      .flatMap(_.get("candidates"))
      .flatMap(_.arrOpt)
      .flatMap(_.headOption)
      .filter(_.objOpt.isDefined)

  /** The first candidate's `finishReason`, when the response (or chunk) states one. */
  def finishReason(json: ujson.Value): Option[String] =
    candidate(json).flatMap(_.obj.get("finishReason")).flatMap(_.strOpt)

  /**
   * The error for a reply a content filter stopped, or for a blocked prompt; `None` for any other response.
   * `priorText` is the answer text a stream delivered before this chunk.
   */
  def filtered(json: ujson.Value, service: String, priorText: Int = 0): Option[ContentFilteredError] =
    candidate(json) match {
      case Some(c) =>
        finishReason(json).filter(FilterFinishes.contains).map { reason =>
          ContentFilteredError.reply(service, reason, cutAfterChars = priorText + answerText(c))
        }
      case None =>
        json.objOpt
          .flatMap(_.get("promptFeedback"))
          .flatMap(_.objOpt)
          .flatMap(_.get("blockReason"))
          .flatMap(_.strOpt)
          .map(reason => ContentFilteredError.prompt(service, reason))
    }

  /** The error for a reply that ended abnormally (not by a filter) having produced no text and no tool call. */
  def unanswered(finishReason: Option[String], reply: Completion, service: String): Option[ValidationError] =
    finishReason
      .filterNot(reason => AnswerFinishes.contains(reason) || FilterFinishes.contains(reason))
      .filter(_ => reply.content.isEmpty && reply.toolCalls.isEmpty && reply.message.toolCalls.isEmpty)
      .map(reason => ValidationError("response", s"$service returned no content: finishReason $reason"))

  /** The characters of answer text (not thought) in a candidate's parts. */
  private def answerText(candidate: ujson.Value): Int =
    GeminiThoughtSignatures
      .partsOf(candidate)
      .flatMap(_.objOpt)
      .filterNot(_.get("thought").exists(_.boolOpt.contains(true)))
      .flatMap(_.get("text").flatMap(_.strOpt))
      .map(_.length)
      .sum
}
