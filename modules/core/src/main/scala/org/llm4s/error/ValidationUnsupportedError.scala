package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * `LLMClient.validate()` was called on a client that has no request to check itself with, so nothing was checked
 * (#2830). It is the default of `validate()`: a provider client returns it when its provider offers no cheap
 * authenticated request, and so does any client that does not implement the check.
 *
 * It says nothing about whether the client works. Match on it to tell "could not check" from "checked, and it
 * failed", and check such a client with a small completion instead.
 *
 * @param client what could not be checked: the client's description, e.g. `BedrockClient`
 */
@Stable
final case class ValidationUnsupportedError private (
  override val message: String,
  client: String
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("client" -> client)
}

object ValidationUnsupportedError {

  /** The error for `client`, which has no validation request. */
  def apply(client: String): ValidationUnsupportedError =
    new ValidationUnsupportedError(
      s"$client has no validation request, so validate() checked nothing: send a small completion to check it",
      client
    )

  /** Extracts the message and the client. */
  def unapply(error: ValidationUnsupportedError): Option[(String, String)] = Some((error.message, error.client))
}
