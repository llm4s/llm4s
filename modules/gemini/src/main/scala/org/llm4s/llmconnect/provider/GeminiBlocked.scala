package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ContentFilteredError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.model.{ Completion, FinishReason }

/**
 * How a Gemini or Vertex AI reply that did not finish normally ends, the same way for `complete` and for a stream:
 *
 *  - A content-filter `finishReason` ([[FilterFinishes]]) is a [[org.llm4s.error.ContentFilteredError]] whether or
 *    not text came before it: a reply the filter cut short is not a finished answer. A stream's text already
 *    delivered stays delivered; the error says how much text the filter cut after. So is a
 *    `promptFeedback.blockReason` with no candidate: the prompt itself was blocked.
 *  - `MALFORMED_FUNCTION_CALL` is the malformed-tool-call [[org.llm4s.error.ProcessingError]] whether or not text came
 *    before it: Gemini dropped a call the model wrote, and a dropped call is never a finished answer (RFC 0009,
 *    Part 3, rule 1).
 *  - Any other `finishReason` but `STOP`, `MAX_TOKENS` or `CONTINUATION` (`UNEXPECTED_TOOL_CALL`, `OTHER`, ...)
 *    is a `ValidationError` naming it when the whole reply has no text and no tool call; otherwise the reply is
 *    returned with what was produced. A stream decides this at its end, from what it accumulated.
 *  - `STOP`, `MAX_TOKENS` or `CONTINUATION` with nothing produced (a thinking model that spent its whole budget
 *    thinking) is an empty answer.
 *
 * A reply that is returned carries its [[mapped]] finish reason (RFC 0009).
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

  /**
   * A `finishReason` as a [[FinishReason]] (RFC 0009): `STOP` is `ToolCalls` when the reply has a function call and
   * `Stop` otherwise, and `MAX_TOKENS` is `Length`. Any other (`CONTINUATION`, `MALFORMED_FUNCTION_CALL`, `OTHER`,
   * ...) is kept as `Other`, and none read as `Other("")`. A filter finish never reaches a reply: [[filtered]] makes
   * it an error.
   */
  def mapped(finishReason: Option[String], hasCalls: Boolean): FinishReason = finishReason match {
    case Some("STOP")       => if (hasCalls) FinishReason.ToolCalls else FinishReason.Stop
    case Some("MAX_TOKENS") => FinishReason.Length
    case Some(other)        => FinishReason.Other(other)
    case None               => FinishReason.Other("")
  }

  /** The first candidate's `finishReason`, when the response (or chunk) states one. */
  def finishReason(json: ujson.Value): Option[String] =
    candidate(json).flatMap(_.obj.get("finishReason")).flatMap(_.strOpt)

  /**
   * The error for a reply a content filter stopped, or for a blocked prompt, with the usage the response reported
   * (#2676); `None` for any other response. `priorText` is the answer text a stream delivered before this chunk.
   */
  def filtered(json: ujson.Value, service: String, priorText: Int = 0): Option[ContentFilteredError] =
    (candidate(json) match {
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
    }).map(_.withUsage(GeminiUsage.fromResponse(json)))

  /** The error for a reply Gemini ended `MALFORMED_FUNCTION_CALL`, having dropped the call the model wrote. */
  def malformedCall(finishReason: Option[String], service: String): Option[ProcessingError] =
    finishReason
      .filter(_ == MalformedFunctionCall)
      .map(reason => ToolCallRules.malformed(s"$service ended the reply $reason: the model's function call was invalid"))

  private val MalformedFunctionCall = "MALFORMED_FUNCTION_CALL"

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
