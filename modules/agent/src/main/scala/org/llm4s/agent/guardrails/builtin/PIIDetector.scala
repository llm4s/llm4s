package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.patterns.PIIPatterns
import org.llm4s.agent.guardrails.patterns.PIIPatterns.{ PIIMatch, PIIType }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Detects Personally Identifiable Information (PII) in text.
 *
 * Uses regex patterns to detect common PII types including:
 * - Social Security Numbers (SSN)
 * - Credit Card Numbers
 * - Email Addresses
 * - Phone Numbers
 * - IP Addresses
 * - Passport Numbers
 * - Dates of Birth
 *
 * Can be configured to:
 * - Block: Return error when PII is detected (default)
 * - Fix: Automatically mask PII and continue
 * - Warn: Log warning and allow processing to continue
 *
 * Example usage:
 * {{{
 * // Block on PII detection
 * val strictDetector = PIIDetector()
 *
 * // Mask PII automatically
 * val maskingDetector = PIIDetector(onFail = GuardrailAction.Fix)
 *
 * // Detect only credit cards and SSNs
 * val financialDetector = PIIDetector(
 *   piiTypes = Seq(PIIType.CreditCard, PIIType.SSN)
 * )
 * }}}
 *
 * @param piiTypes The types of PII to detect (default: SSN, CreditCard, Email, Phone)
 * @param onFail Action to take when PII is detected (default: Block)
 */
class PIIDetector(
  val piiTypes: Seq[PIIType] = PIIType.default,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends InputGuardrail
    with OutputGuardrail {

  def validate(value: String): Result[String] = {
    val matches = PIIPatterns.detect(value, piiTypes)

    if (matches.isEmpty) {
      Right(value)
    } else {
      onFail match {
        case GuardrailAction.Block =>
          val summary  = summarizeMatches(matches)
          val piiTypes = matches.map(_.piiType.name).distinct.mkString(", ")
          Left(
            ValidationError.invalid(
              "input",
              s"PII detected: $summary. Found types: [$piiTypes]. " +
                "Remove or mask sensitive information before processing."
            )
          )

        case GuardrailAction.Fix =>
          // Mask all detected PII and return the sanitized text
          val masked = PIIPatterns.maskAll(value, piiTypes)
          Right(masked)

        case GuardrailAction.Warn =>
          // Log warning but allow processing
          // In a real implementation, this would log to the trace system
          Right(value)
      }
    }
  }

  /**
   * Transform applies masking when in Fix mode.
   * For other modes, returns input unchanged.
   */
  override def transform(input: String): String =
    onFail match {
      case GuardrailAction.Fix => PIIPatterns.maskAll(input, piiTypes)
      case _                   => input
    }

  val name: String = "PIIDetector"

  override val description: Option[String] = Some(
    s"Detects PII in text: ${piiTypes.map(_.name).mkString(", ")}"
  )

  private def summarizeMatches(matches: Seq[PIIMatch]): String = {
    val grouped = matches.groupBy(_.piiType.name)
    grouped
      .map { case (typeName, typeMatches) =>
        s"$typeName (${typeMatches.size})"
      }
      .mkString(", ")
  }
}

object PIIDetector {

  /**
   * Create a PIIDetector with default settings (Block mode, standard PII types).
   */
  def apply(): PIIDetector = new PIIDetector()

  /**
   * Create a PIIDetector with custom PII types.
   *
   * @param piiTypes Types of PII to detect
   */
  def apply(piiTypes: Seq[PIIType]): PIIDetector =
    new PIIDetector(piiTypes = piiTypes)

  /**
   * Create a PIIDetector with custom action mode.
   *
   * @param onFail Action to take when PII is detected
   */
  def apply(onFail: GuardrailAction): PIIDetector =
    new PIIDetector(onFail = onFail)

  /**
   * Create a PIIDetector with custom settings.
   *
   * @param piiTypes Types of PII to detect
   * @param onFail Action to take when PII is detected
   */
  def apply(piiTypes: Seq[PIIType], onFail: GuardrailAction): PIIDetector =
    new PIIDetector(piiTypes = piiTypes, onFail = onFail)

  /**
   * Preset: Strict mode - blocks on any PII detection.
   * Detects all PII types.
   */
  def strict: PIIDetector =
    new PIIDetector(piiTypes = PIIType.all, onFail = GuardrailAction.Block)

  /**
   * Preset: Masking mode - automatically redacts detected PII.
   * Detects default PII types.
   */
  def masking: PIIDetector =
    new PIIDetector(onFail = GuardrailAction.Fix)

  /**
   * Preset: Monitoring mode - warns but allows processing.
   * Detects default PII types.
   */
  def monitoring: PIIDetector =
    new PIIDetector(onFail = GuardrailAction.Warn)

  /**
   * Preset: Financial - detects credit cards, bank accounts, SSNs.
   * Strict mode (blocks on detection).
   */
  def financial: PIIDetector =
    new PIIDetector(
      piiTypes = Seq(PIIType.CreditCard, PIIType.SSN, PIIType.BankAccount),
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Contact info - detects emails and phone numbers.
   * Masking mode (redacts on detection).
   */
  def contactInfo: PIIDetector =
    new PIIDetector(
      piiTypes = Seq(PIIType.Email, PIIType.Phone),
      onFail = GuardrailAction.Fix
    )
}
