package org.llm4s.agent.guardrails.builtin

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.security.RegexSafetyManager
import org.llm4s.types.Result

import java.util.regex.Pattern
import scala.util.matching.Regex

/**
 * Rejects text in which a regular expression finds no match.
 *
 * Works as both an [[InputGuardrail]] and an [[OutputGuardrail]]. Use it to enforce a format (an email address,
 * a phone number, an order id) or to require that some text is present.
 *
 * The pattern is searched for, not matched against the whole text: `[0-9]+` accepts `order 66`. Anchor the
 * pattern (`^[0-9]+$`) to require that the whole text fits, with one caveat: `$` also matches before a single
 * final line terminator (`\n`, `\r\n`, `\r`, or one of U+0085, U+2028 and U+2029), so `^[0-9]+$` accepts `66`
 * followed by one line break, though not by two line breaks or by a space. `.` does not match a line terminator
 * unless the `(?s)` (DOTALL) flag is set, and `^` and `$` anchor to the start and end of the text unless `(?m)`
 * is set.
 *
 * Patterns are user-supplied, so matching goes through [[org.llm4s.security.RegexSafetyManager]], whichever
 * way the validator was built: text that is `null` or longer than 100000 characters is rejected before
 * matching, and a match that exceeds its character-access budget (the guard against catastrophic backtracking)
 * is aborted. Only the companion's `String` factories also pre-screen the pattern itself: there a blank
 * pattern, one longer than 1000 characters, or one with a known catastrophic-backtracking shape is refused. The
 * shape check is a heuristic: it refuses a group followed by `+` or `*` that itself contains `+`, `*` or `|`
 * (`(a+)+`, `(cat|dog)*`), so some harmless patterns are refused, and it does not catch every dangerous one. A
 * pattern passed as a [[scala.util.matching.Regex]] or a compiled `java.util.regex.Pattern` (the constructors,
 * `RegexValidator(regex)`, [[RegexValidator.email]], [[RegexValidator.phone]] and
 * [[RegexValidator.alphanumeric]]) is used as given, with no pre-screen; its matching is still bounded.
 *
 * `validate` reports failures as a [[org.llm4s.error.ValidationError]] for the field `value`. Its detail is
 * `errorMessage` when one was given, else `Value does not match pattern: <pattern>`, for a plain mismatch.
 * Other failures use their own detail: `Regex security error: <reason>` when the text is `null` or too long, or
 * the match is aborted or otherwise fails, and `Invalid or unsafe regex pattern: <reason>` for every text when
 * the pattern was given as a `String` and was refused or did not compile. That last failure replaces any custom
 * message, so a bad pattern shows up on the first validation rather than as a startup error.
 *
 * One failure is not caught: the JDK regex engine recurses as it matches some patterns, such as `(a|aa)*b`,
 * and on long enough text it can throw `StackOverflowError` before reaching the character-access budget. The
 * safety manager does not catch that error, so `validate` propagates it instead of returning a `Left`. The
 * `String` factories' pre-screen refuses some of these shapes (including that one), but not all of them.
 *
 * Prefer the `String` factories (`RegexValidator("...")` and `RegexValidator("...", errorMessage)`) for
 * patterns that are not fixed in code: they are the only ones that compile through
 * [[org.llm4s.security.RegexSafetyManager.safeCompile]] and pre-screen the pattern.
 *
 * @example
 * {{{
 * import org.llm4s.agent.guardrails.builtin.RegexValidator
 *
 * val orderId = RegexValidator("^ORD-[0-9]{6}$", "Order ids look like ORD-123456")
 *
 * orderId.validate("ORD-123456")   // Right("ORD-123456")
 * orderId.validate("ORD-123456\n") // Right: `$` also accepts one trailing line break
 * orderId.validate("ORD-12")       // Left(ValidationError): "Order ids look like ORD-123456"
 *
 * agent.run(query, tools, inputGuardrails = Seq(RegexValidator.email))
 * }}}
 *
 * @param compiledPattern    the pattern to search for, already compiled; used as given, without the safety
 *                           manager's pre-screen
 * @param patternDescription the pattern's source text, used in the default error and in `description`
 * @param errorMessage       the detail to report on a plain mismatch; the default message names the pattern
 * @param fallbackError      when set, `validate` fails every text with this detail, ignoring the pattern; the
 *                           companion's `String` factories set it when the pattern is refused or does not
 *                           compile
 */
class RegexValidator(
  compiledPattern: Pattern,
  patternDescription: String,
  errorMessage: Option[String] = None,
  fallbackError: Option[String] = None
) extends InputGuardrail
    with OutputGuardrail {

  /**
   * Build a validator from a Scala [[scala.util.matching.Regex]].
   *
   * The pattern is used as given, without the safety manager's pre-screen of its shape and length; matching is
   * still bounded.
   *
   * @param pattern       the pattern to search for
   * @param errorMessage  the detail to report on a plain mismatch
   * @param fallbackError when set, `validate` fails every text with this detail
   */
  def this(pattern: Regex, errorMessage: Option[String], fallbackError: Option[String]) =
    this(pattern.pattern, pattern.toString, errorMessage, fallbackError)

  /**
   * Build a validator from a Scala [[scala.util.matching.Regex]] with a custom mismatch message.
   *
   * @param pattern      the pattern to search for
   * @param errorMessage the detail to report on a plain mismatch
   */
  def this(pattern: Regex, errorMessage: Option[String]) =
    this(pattern.pattern, pattern.toString, errorMessage, None)

  /**
   * Build a validator from a Scala [[scala.util.matching.Regex]] with the default mismatch message.
   *
   * @param pattern the pattern to search for
   */
  def this(pattern: Regex) =
    this(pattern.pattern, pattern.toString, None, None)

  /**
   * Search `value` for the pattern.
   *
   * @param value the text to check
   * @return `Right(value)` unchanged when the pattern is found, otherwise `Left` with a
   *         [[org.llm4s.error.ValidationError]] for the field `value`; see the class documentation for the
   *         detail of each failure, and for the `StackOverflowError` that can escape on a deeply recursive
   *         match
   */
  def validate(value: String): Result[String] =
    fallbackError match {
      case Some(error) => Left(ValidationError.invalid("value", error))
      case None =>
        RegexSafetyManager.safeFind(compiledPattern, value) match {
          case Right(true) => Right(value)
          case Right(false) =>
            Left(
              ValidationError.invalid(
                "value",
                errorMessage.getOrElse(s"Value does not match pattern: $patternDescription")
              )
            )
          case Left(error) =>
            Left(ValidationError.invalid("value", s"Regex security error: $error"))
        }
    }

  val name: String = "RegexValidator"

  override val description: Option[String] = Some(
    s"Validates against pattern: $patternDescription"
  )

  // Resolve conflicting transform methods from both traits
  override def transform(input: String): String = input
}

object RegexValidator {

  /**
   * Create a validator that searches for a pattern given as a string.
   *
   * The pattern goes through [[org.llm4s.security.RegexSafetyManager.safeCompile]]. One that is refused or does not
   * compile does not throw here: the validator is still returned, and every `validate` call fails with
   * `Invalid or unsafe regex pattern: <reason>`.
   *
   * @param pattern the regular expression to search for, in `java.util.regex` syntax
   * @return a validator whose mismatch message is `Value does not match pattern: <pattern>`
   */
  def apply(pattern: String): RegexValidator =
    RegexSafetyManager.safeCompile(pattern) match {
      case Right(compiled) => new RegexValidator(compiled, pattern)
      case Left(error) =>
        new RegexValidator(
          Pattern.compile("(?!)"),
          pattern,
          fallbackError = Some(s"Invalid or unsafe regex pattern: $error")
        )
    }

  /**
   * Create a validator that searches for a [[scala.util.matching.Regex]].
   *
   * The pattern is used as given: unlike the `String` factories it is not pre-screened for dangerous shapes or
   * length, though matching is still bounded.
   *
   * @param pattern the pattern to search for
   * @return a validator whose mismatch message is `Value does not match pattern: <pattern>`
   */
  def apply(pattern: Regex): RegexValidator =
    new RegexValidator(pattern)

  /**
   * Create a validator for a string pattern with a custom mismatch message.
   *
   * As with the one-argument factory, a refused or invalid pattern fails every `validate` call with
   * `Invalid or unsafe regex pattern: <reason>`; `errorMessage` is not used for that failure.
   *
   * @param pattern      the regular expression to search for, in `java.util.regex` syntax
   * @param errorMessage the detail to report when the pattern is not found
   * @return the validator
   */
  def apply(pattern: String, errorMessage: String): RegexValidator =
    RegexSafetyManager.safeCompile(pattern) match {
      case Right(compiled) => new RegexValidator(compiled, pattern, Some(errorMessage))
      case Left(error) =>
        new RegexValidator(
          Pattern.compile("(?!)"),
          pattern,
          Some(errorMessage),
          Some(s"Invalid or unsafe regex pattern: $error")
        )
    }

  /**
   * A validator for email addresses, using a basic pattern.
   *
   * Accepts `local@domain.tld` with letters, digits and `+ _ . -` in the local part. It is a format check, not
   * proof that the address exists, and it does not implement the full email grammar. The pattern ends with `$`,
   * which also accepts one trailing line break. A mismatch reports `Invalid email address format`.
   */
  def email: RegexValidator = new RegexValidator(
    "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".r,
    Some("Invalid email address format")
  )

  /**
   * A validator for phone numbers, using a basic pattern: an optional leading `+` and then 10 to 15 digits, with
   * no spaces, dashes or brackets. The pattern ends with `$`, which also accepts one trailing line break. A
   * mismatch reports `Invalid phone number format`.
   */
  def phone: RegexValidator = new RegexValidator(
    "^\\+?[0-9]{10,15}$".r,
    Some("Invalid phone number format")
  )

  /**
   * A validator for non-empty text made only of ASCII letters and digits, so spaces and punctuation are
   * rejected. The pattern ends with `$`, which also accepts one trailing line break (`abc` followed by a line
   * break passes). A mismatch reports `Content must be alphanumeric`.
   */
  def alphanumeric: RegexValidator = new RegexValidator(
    "^[A-Za-z0-9]+$".r,
    Some("Content must be alphanumeric")
  )
}
