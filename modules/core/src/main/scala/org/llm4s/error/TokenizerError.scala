package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Tokenizer-related errors for context management
 */
@Stable
final case class TokenizerError private (
  override val message: String,
  tokenizerId: String
) extends LLMError {
  override val context: Map[String, String] = Map("tokenizerId" -> tokenizerId)
}

object TokenizerError {
  def notAvailable(tokenizerId: String): TokenizerError =
    new TokenizerError(s"Tokenizer not available: $tokenizerId", tokenizerId)

  /** Unapply extractor for pattern matching */
  def unapply(error: TokenizerError): Option[(String, String)] =
    Some((error.message, error.tokenizerId))
}
