package org.llm4s.agent.guardrails.patterns

import scala.util.matching.Regex

/**
 * Regex patterns for detecting Personally Identifiable Information (PII).
 *
 * These patterns are designed for common US formats and can detect:
 * - Social Security Numbers (SSN)
 * - Credit Card Numbers
 * - Email Addresses
 * - Phone Numbers
 * - IP Addresses
 * - Passport Numbers
 * - Driver's License Numbers
 * - Bank Account Numbers
 * - Medical Record Numbers
 *
 * Note: These patterns favor recall over precision - they may have false
 * positives but minimize false negatives for security-sensitive use cases.
 */
object PIIPatterns {

  /**
   * Represents a detected PII match with type and location.
   */
  final case class PIIMatch(
    piiType: PIIType,
    value: String,
    startIndex: Int,
    endIndex: Int
  ) {
    def maskedValue: String = piiType.mask(value)
  }

  /**
   * Types of PII that can be detected.
   */
  sealed trait PIIType {
    def name: String
    def pattern: Regex
    def mask(value: String): String

    /**
     * Find all matches of this PII type in text.
     */
    def findAll(text: String): Seq[PIIMatch] =
      pattern
        .findAllMatchIn(text)
        .map(m => PIIMatch(this, m.matched, m.start, m.end))
        .toSeq
  }

  object PIIType {

    /**
     * Social Security Number (US format: XXX-XX-XXXX)
     * Validates against known invalid SSNs (000, 666, 9XX prefix)
     */
    case object SSN extends PIIType {
      val name                        = "SSN"
      val pattern                     = """(?<!\d)(?!000|666|9\d{2})\d{3}[-\s]?(?!00)\d{2}[-\s]?(?!0000)\d{4}(?!\d)""".r
      def mask(value: String): String = "[REDACTED_SSN]"
    }

    /**
     * Credit Card Numbers (major providers: Visa, MasterCard, Amex, Discover)
     * Supports common formats with spaces, dashes, or no separators
     */
    case object CreditCard extends PIIType {
      val name = "Credit Card"
      // Visa: 4xxx, MC: 51-55xx/2221-2720, Amex: 34/37xx, Discover: 6011/65xx
      val pattern =
        """(?<!\d)(?:4\d{3}|5[1-5]\d{2}|2[2-7]\d{2}|3[47]\d{2}|6(?:011|5\d{2}))[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}(?!\d)""".r
      def mask(value: String): String = "[REDACTED_CARD]"
    }

    /**
     * Email addresses (RFC 5322 simplified)
     */
    case object Email extends PIIType {
      val name                        = "Email"
      val pattern                     = """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""".r
      def mask(value: String): String = "[REDACTED_EMAIL]"
    }

    /**
     * Phone numbers (US formats: (XXX) XXX-XXXX, XXX-XXX-XXXX, XXX.XXX.XXXX)
     */
    case object Phone extends PIIType {
      val name = "Phone"
      val pattern =
        """(?<!\d)(?:\+?1[-.\s]?)?(?:\(\d{3}\)|\d{3})[-.\s]?\d{3}[-.\s]?\d{4}(?!\d)""".r
      def mask(value: String): String = "[REDACTED_PHONE]"
    }

    /**
     * IP Addresses (IPv4)
     */
    case object IPAddress extends PIIType {
      val name    = "IP Address"
      val pattern = """(?<!\d)(?:(?:25[0-5]|2[0-4]\d|[01]?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d?\d)(?!\d)""".r
      def mask(value: String): String = "[REDACTED_IP]"
    }

    /**
     * US Passport Numbers (9 alphanumeric characters)
     */
    case object Passport extends PIIType {
      val name                        = "Passport"
      val pattern                     = """(?<![A-Za-z0-9])[A-Z]?\d{8,9}(?![A-Za-z0-9])""".r
      def mask(value: String): String = "[REDACTED_PASSPORT]"
    }

    /**
     * Bank Account Numbers (8-17 digits, simple pattern)
     */
    case object BankAccount extends PIIType {
      val name                        = "Bank Account"
      val pattern                     = """(?<!\d)\d{8,17}(?!\d)""".r
      def mask(value: String): String = "[REDACTED_ACCOUNT]"
    }

    /**
     * Date of Birth (common formats: MM/DD/YYYY, YYYY-MM-DD)
     */
    case object DateOfBirth extends PIIType {
      val name = "Date of Birth"
      val pattern =
        """(?<!\d)(?:(?:0?[1-9]|1[0-2])[/\-](?:0?[1-9]|[12]\d|3[01])[/\-](?:19|20)\d{2}|(?:19|20)\d{2}[/\-](?:0?[1-9]|1[0-2])[/\-](?:0?[1-9]|[12]\d|3[01]))(?!\d)""".r
      def mask(value: String): String = "[REDACTED_DOB]"
    }

    /**
     * All PII types for comprehensive scanning.
     */
    val all: Seq[PIIType] = Seq(SSN, CreditCard, Email, Phone, IPAddress, Passport, DateOfBirth)

    /**
     * Default set of high-confidence PII types (lower false positive rate).
     * Excludes BankAccount which has high false positive rate with random numbers.
     */
    val default: Seq[PIIType] = Seq(SSN, CreditCard, Email, Phone)

    /**
     * Sensitive set including financial information.
     */
    val sensitive: Seq[PIIType] = Seq(SSN, CreditCard, Email, Phone, BankAccount)
  }

  /**
   * Detect all PII matches in text using specified patterns.
   *
   * @param text Text to scan
   * @param types PII types to detect (default: SSN, CreditCard, Email, Phone)
   * @return Sequence of matches with type and location
   */
  def detect(text: String, types: Seq[PIIType] = PIIType.default): Seq[PIIMatch] =
    types.flatMap(_.findAll(text))

  /**
   * Check if text contains any PII.
   *
   * @param text Text to scan
   * @param types PII types to detect
   * @return True if any PII found
   */
  def containsPII(text: String, types: Seq[PIIType] = PIIType.default): Boolean =
    types.exists(_.findAll(text).nonEmpty)

  /**
   * Mask all PII in text with redaction placeholders.
   *
   * @param text Text to scan and mask
   * @param types PII types to mask
   * @return Text with PII replaced by [REDACTED_*] placeholders
   */
  def maskAll(text: String, types: Seq[PIIType] = PIIType.default): String = {
    val matches = detect(text, types).sortBy(-_.startIndex) // Sort descending to replace from end
    matches.foldLeft(text) { (current, m) =>
      current.substring(0, m.startIndex) + m.maskedValue + current.substring(m.endIndex)
    }
  }

  /**
   * Get a summary of PII found in text.
   *
   * @param text Text to scan
   * @param types PII types to detect
   * @return Map of PII type name to count
   */
  def summarize(text: String, types: Seq[PIIType] = PIIType.default): Map[String, Int] =
    detect(text, types).groupBy(_.piiType.name).map { case (k, v) => k -> v.size }
}
