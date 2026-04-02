package org.llm4s.security

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.control.NonFatal

/**
 * Safety wrapper for user-supplied regex compilation and matching.
 *
 * This manager blocks known catastrophic patterns and enforces basic bounds
 * so we can avoid relying on interruption-based regex timeouts.
 */
object RegexSafetyManager {

  private val MaxPatternLength            = 1000
  private val MaxInputLength              = 100000
  private val DefaultCompilationTimeoutMs = 1000L
  private val DefaultMatchingTimeoutMs    = 250L
  private val DefaultCaseInsensitiveFlags = Pattern.CASE_INSENSITIVE

  // Common catastrophic-backtracking shapes.
  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]", // ((x+)+)+ or ((x*)*)*
    "\\([^)]*[+*][^)]*\\)[+*]",                // (x+)+, (x*)*, (x+)*
    "\\([^)]*\\|[^)]*\\)\\*"                   // (x|y)*
  ).map(_.r)

  def safeCompile(
    pattern: String,
    flags: Int = 0,
    timeoutMs: Long = DefaultCompilationTimeoutMs
  ): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags, timeoutMs)
    } yield p

  def safeFind(pattern: Pattern, input: String, timeoutMs: Long = DefaultMatchingTimeoutMs): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => findSafely(pattern, input, timeoutMs))

  def safeMatches(
    pattern: Pattern,
    input: String,
    timeoutMs: Long = DefaultMatchingTimeoutMs
  ): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => matchesSafely(pattern, input, timeoutMs))

  def compileLiteral(pattern: String, flags: Int = 0): Pattern =
    Pattern.compile(Pattern.quote(pattern), flags)

  def compileLiteralCaseInsensitive(pattern: String): Pattern =
    compileLiteral(pattern, DefaultCaseInsensitiveFlags)

  private def validatePattern(pattern: String): Either[String, Unit] =
    if (pattern == null) Left("Pattern cannot be null")
    else if (pattern.trim.isEmpty) Left("Pattern cannot be empty")
    else if (pattern.length > MaxPatternLength)
      Left(s"Pattern too long: ${pattern.length} > $MaxPatternLength")
    else if (DangerousPatternShapes.exists(_.findFirstIn(pattern).isDefined))
      Left("Regex pattern contains nested quantifiers/overlap and may cause ReDoS")
    else Right(())

  private def withInputValidation(input: String): Either[String, Unit] =
    if (input == null) Left("Input cannot be null")
    else if (input.length > MaxInputLength)
      Left(s"Input too large: ${input.length} > $MaxInputLength")
    else Right(())

  private def compileSafely(pattern: String, flags: Int, timeoutMs: Long): Either[String, Pattern] =
    try {
      // timeoutMs kept for API compatibility; execution safety comes from pre-checks.
      val _ = timeoutMs
      Right(Pattern.compile(pattern, flags))
    } catch {
      case e: PatternSyntaxException => Left(s"Invalid regex syntax: ${e.getMessage}")
      case NonFatal(e)               => Left(s"Regex compilation failed: ${e.getMessage}")
    }

  private def findSafely(pattern: Pattern, input: String, timeoutMs: Long): Either[String, Boolean] =
    try {
      val _ = timeoutMs
      Right(pattern.matcher(input).find())
    } catch {
      case NonFatal(e) => Left(s"Regex matching failed: ${e.getMessage}")
    }

  private def matchesSafely(pattern: Pattern, input: String, timeoutMs: Long): Either[String, Boolean] =
    try {
      val _ = timeoutMs
      Right(pattern.matcher(input).matches())
    } catch {
      case NonFatal(e) => Left(s"Regex matching failed: ${e.getMessage}")
    }

  def replaceAllLiteral(
    input: String,
    literalPattern: String,
    replacement: String,
    caseInsensitive: Boolean
  ): String = {
    val flags   = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
    val pattern = compileLiteral(literalPattern, flags)
    pattern.matcher(input).replaceAll(Matcher.quoteReplacement(replacement))
  }

  def replaceFirstLiteral(
    input: String,
    literalPattern: String,
    replacement: String,
    caseInsensitive: Boolean
  ): String = {
    val flags   = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
    val pattern = compileLiteral(literalPattern, flags)
    pattern.matcher(input).replaceFirst(Matcher.quoteReplacement(replacement))
  }
}
