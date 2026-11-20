package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ContentFilteredError, LLMError, ValidationError }

/**
 * A Gemini or Vertex AI response that holds no answer, read the same way by `complete` and by each streamed chunk:
 *
 *  - a candidate with no parts (no `content`, a null one, one without `parts` or with an empty `parts`) and a
 *    `finishReason` other than `STOP` or `MAX_TOKENS`. A content-filter reason (`SAFETY`, `RECITATION`,
 *    `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`, `IMAGE_SAFETY`) is a [[org.llm4s.error.ContentFilteredError]];
 *    any other (`MALFORMED_FUNCTION_CALL`, `OTHER`, ...) is a `ValidationError`. Both name the reason.
 *  - no candidate, and a `promptFeedback.blockReason`: the prompt itself was blocked, a `ContentFilteredError`.
 *
 * A candidate with no parts and a `STOP` or `MAX_TOKENS` finish (a thinking model that spent its whole budget
 * thinking), or with no finish reason yet (a stream's chunk), is an empty answer, not an error.
 */
private[provider] object GeminiBlocked {

  private val AnswerFinishes = Set("STOP", "MAX_TOKENS")

  private val FilterFinishes = Set("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")

  /** The error for a response with no answer, or `None` when `json` is not one. `service` names it in the message. */
  def error(json: ujson.Value, service: String): Option[LLMError] =
    json.objOpt.flatMap { root =>
      root.get("candidates").flatMap(_.arrOpt).flatMap(_.headOption).filter(_.objOpt.isDefined) match {
        case Some(candidate) =>
          candidate.obj
            .get("finishReason")
            .flatMap(_.strOpt)
            .filter(reason => GeminiThoughtSignatures.partsOf(candidate).isEmpty && !AnswerFinishes.contains(reason))
            .map { reason =>
              if (FilterFinishes.contains(reason)) ContentFilteredError.reply(service, reason)
              else ValidationError("response", s"$service returned no content: finishReason $reason")
            }
        case None =>
          root
            .get("promptFeedback")
            .flatMap(_.objOpt)
            .flatMap(_.get("blockReason"))
            .flatMap(_.strOpt)
            .map(reason => ContentFilteredError.prompt(service, reason))
      }
    }
}
