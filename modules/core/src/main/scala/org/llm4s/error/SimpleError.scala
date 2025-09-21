package org.llm4s.error

final case class SimpleError private (
  override val message: String
) extends LLMError
    with NonRecoverableError

object SimpleError {
  def apply(message: String): SimpleError = new SimpleError(message)

  def unapply(error: SimpleError): Option[(String, Map[String, String])] =
    Some((error.message, error.context))
}
