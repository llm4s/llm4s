package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.patterns.PIIPatterns
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.types.Result

/**
 * Automatically masks Personally Identifiable Information (PII) in text.
 *
 * Unlike PIIDetector (which can block or warn), PIIMasker always transforms
 * the text by replacing detected PII with redaction placeholders.
 *
 * This guardrail never blocks - it always allows processing to continue
 * with sanitized text. Use this when you want to:
 * - Sanitize user input before sending to LLM
 * - Redact sensitive information from LLM outputs
 * - Preserve privacy while allowing queries to proceed
 *
 * Masked text uses placeholders like [REDACTED_EMAIL], [REDACTED_SSN], etc.
 *
 * Example usage:
 * {{{
 * // Mask all default PII types
 * val masker = PIIMasker()
 *
 * // Mask only specific types
 * val emailMasker = PIIMasker(Seq(PIIType.Email, PIIType.Phone))
 *
 * // Use with agent
 * agent.run(
 *   query = userInput,
 *   tools = tools,
 *   inputGuardrails = Seq(PIIMasker())
 * )
 * }}}
 *
 * @param piiTypes The types of PII to mask (default: SSN, CreditCard, Email, Phone)
 */
class PIIMasker(
  val piiTypes: Seq[PIIType] = PIIType.default
) extends InputGuardrail
    with OutputGuardrail {

  /**
   * Validate always succeeds but transforms text by masking PII.
   */
  def validate(value: String): Result[String] =
    Right(PIIPatterns.maskAll(value, piiTypes))

  /**
   * Transform masks all detected PII in the input.
   */
  override def transform(input: String): String =
    PIIPatterns.maskAll(input, piiTypes)

  val name: String = "PIIMasker"

  override val description: Option[String] = Some(
    s"Masks PII in text: ${piiTypes.map(_.name).mkString(", ")}"
  )

  /**
   * Check if text contains any PII that will be masked.
   */
  def containsPII(text: String): Boolean =
    PIIPatterns.containsPII(text, piiTypes)

  /**
   * Get summary of PII that will be masked.
   */
  def summarizePII(text: String): Map[String, Int] =
    PIIPatterns.summarize(text, piiTypes)
}

object PIIMasker {

  /**
   * Create a PIIMasker with default PII types.
   */
  def apply(): PIIMasker = new PIIMasker()

  /**
   * Create a PIIMasker with custom PII types.
   *
   * @param piiTypes Types of PII to mask
   */
  def apply(piiTypes: Seq[PIIType]): PIIMasker =
    new PIIMasker(piiTypes = piiTypes)

  /**
   * Preset: All PII types for maximum privacy protection.
   */
  def all: PIIMasker = new PIIMasker(piiTypes = PIIType.all)

  /**
   * Preset: Financial information (credit cards, SSNs, bank accounts).
   */
  def financial: PIIMasker =
    new PIIMasker(piiTypes = Seq(PIIType.CreditCard, PIIType.SSN, PIIType.BankAccount))

  /**
   * Preset: Contact information (emails, phone numbers).
   */
  def contactInfo: PIIMasker =
    new PIIMasker(piiTypes = Seq(PIIType.Email, PIIType.Phone))

  /**
   * Preset: Identity documents (SSN, passport, date of birth).
   */
  def identity: PIIMasker =
    new PIIMasker(piiTypes = Seq(PIIType.SSN, PIIType.Passport, PIIType.DateOfBirth))

  /**
   * Preset: Sensitive data (combines financial and identity).
   */
  def sensitive: PIIMasker =
    new PIIMasker(piiTypes = PIIType.sensitive)
}
