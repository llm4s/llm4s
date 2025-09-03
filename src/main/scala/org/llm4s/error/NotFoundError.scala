package org.llm4s.error

/** Missing configuration/environment value error */
final case class NotFoundError(
  override val message: String,
  key: String
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("key" -> key)
}
