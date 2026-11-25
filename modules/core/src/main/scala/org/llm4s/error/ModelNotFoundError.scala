package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.types.ProviderModelTypes.ProviderId

/**
 * The provider does not know the model the request named, or the credential has no access to it.
 *
 * It is not [[LLMError.isRetryable retryable]]: the same request names the same model. The caller corrects the
 * section's `model` (`Llm4sConfig.listModels()` lists what the provider offers), pulls it (Ollama), or falls back
 * to another provider. It is a type of its own, not a [[ServiceError]] with status 404, because a 404 is also what
 * a wrong `baseUrl` answers, and only the provider's reply tells the two apart.
 *
 * [[NotFoundError]] is a missing configuration value, not this.
 *
 * @param message    the provider's explanation
 * @param provider   the provider that refused the request
 * @param model      the model the request named, when the client that mapped the error knows it
 * @param httpStatus the HTTP status of the provider's response (usually 404), when mapped from one
 * @param requestId  the provider's id for the refused request, when the response carried one
 */
@Stable
final case class ModelNotFoundError private (
  override val message: String,
  provider: ProviderId,
  model: Option[String],
  override val httpStatus: Option[Int],
  override val requestId: Option[String]
) extends LLMError {

  override val code: Option[String] = Some(ModelNotFoundError.Code)

  /** This error, naming the model the request asked for. */
  def withModel(model: String): ModelNotFoundError = copy(model = Some(model))

  /** This error, carrying the HTTP status of the response it was mapped from. */
  def withHttpStatus(status: Int): ModelNotFoundError = copy(httpStatus = Some(status))

  /** This error, carrying the provider's id for the refused request. */
  def withRequestId(id: String): ModelNotFoundError = copy(requestId = Some(id))

  /** This error with `sentence` and a troubleshooting link appended to its message (#2285). */
  private[llm4s] def withHint(sentence: String, entry: TroubleshootingLinks.Entry): ModelNotFoundError =
    copy(message = TroubleshootingLinks.withAction(message, sentence, entry))

  override val context: Map[String, String] = Map("provider" -> provider.asString) ++
    model.map("model" -> _) ++ requestId.map("requestId" -> _)
}

object ModelNotFoundError {

  /** The [[LLMError.code]] of every model-not-found error, whichever provider reported it. */
  val Code: String = "model_not_found"

  /** `provider` does not know the model the request named, explaining it with `details`. */
  def apply(provider: ProviderId, details: String): ModelNotFoundError =
    new ModelNotFoundError(s"$provider does not know the model: $details", provider, None, None, None)

  /** Extracts the message, provider and model; the arity is fixed. */
  def unapply(error: ModelNotFoundError): Some[(String, ProviderId, Option[String])] =
    Some((error.message, error.provider, error.model))
}
