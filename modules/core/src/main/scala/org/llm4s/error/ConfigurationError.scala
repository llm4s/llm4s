package org.llm4s.error

final case class ConfigurationError private (
  override val message: String,
  missingKeys: List[String]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] =
    missingKeys.headOption.fold(Map.empty[String, String])(_ => Map("missingKeys" -> missingKeys.mkString(", ")))
}

object ConfigurationError {
  def apply(message: String, missingKeys: List[String] = List.empty): ConfigurationError =
    new ConfigurationError(message, missingKeys)

  /** Unapply extractor for pattern matching */
  def unapply(error: ConfigurationError): Option[(String, List[String])] =
    Some((error.message, error.missingKeys))
}
