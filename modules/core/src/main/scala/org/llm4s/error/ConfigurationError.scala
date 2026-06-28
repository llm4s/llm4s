package org.llm4s.error

/**
 * Raised when there is an issue with the client or operation configuration.
 *
 * This is a [[NonRecoverableError]]: it indicates a permanent failure due to missing or
 * invalid configuration values (e.g., missing base URL, invalid model name).
 * The user must correct the configuration to resolve this error.
 *
 * @param message human-readable description of the configuration error
 * @param missingKeys list of configuration keys that were missing or invalid
 */
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
