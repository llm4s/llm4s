package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * The provider refused the request because the conversation, with the reply it was asked to leave room for, does
 * not fit the model's context window.
 *
 * It is not [[LLMError.isRetryable retryable]]: the same request overflows again. The caller shortens the
 * conversation (`org.llm4s.context`, or the agent's `ContextWindowMiddleware`), lowers `maxTokens`, or moves to a
 * model with a larger window. It is a type of its own, not a [[ValidationError]], so that a caller can react to it
 * without reading the provider's message.
 *
 * @param message         the provider's explanation
 * @param provider        the provider that refused the request
 * @param limit           the model's context window in tokens, when the provider's message states it
 * @param requestedTokens the tokens the request needed, when the provider's message states it
 * @param httpStatus      the HTTP status of the provider's response (usually 400), when mapped from one
 * @param requestId       the provider's id for the refused request, when the response carried one
 */
@Stable
final case class ContextLengthError private (
  override val message: String,
  provider: String,
  limit: Option[Int],
  requestedTokens: Option[Int],
  override val httpStatus: Option[Int],
  override val requestId: Option[String]
) extends LLMError {

  override val code: Option[String] = Some(ContextLengthError.Code)

  /** This error, carrying the model's context window and the tokens the request needed. */
  def withTokens(limit: Option[Int], requestedTokens: Option[Int]): ContextLengthError =
    copy(limit = limit, requestedTokens = requestedTokens)

  /** This error, carrying the HTTP status of the response it was mapped from. */
  def withHttpStatus(status: Int): ContextLengthError = copy(httpStatus = Some(status))

  /** This error, carrying the provider's id for the refused request. */
  def withRequestId(id: String): ContextLengthError = copy(requestId = Some(id))

  override val context: Map[String, String] = Map("provider" -> provider) ++
    limit.map("limit" -> _.toString) ++ requestedTokens.map("requestedTokens" -> _.toString) ++
    requestId.map("requestId" -> _)
}

object ContextLengthError {

  /** The [[LLMError.code]] of every context-length error, whichever provider reported it. */
  val Code: String = "context_length_exceeded"

  /** `provider` refused the request as too long for the model, explaining it with `details`. */
  def apply(provider: String, details: String): ContextLengthError =
    new ContextLengthError(
      s"$provider refused the request: it does not fit the model's context window: $details",
      provider,
      None,
      None,
      None,
      None
    )

  /** Extracts the message, provider, limit and requested tokens; the arity is fixed. */
  def unapply(error: ContextLengthError): Some[(String, String, Option[Int], Option[Int])] =
    Some((error.message, error.provider, error.limit, error.requestedTokens))
}
