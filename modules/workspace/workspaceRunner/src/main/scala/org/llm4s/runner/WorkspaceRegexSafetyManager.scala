package org.llm4s.runner

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.control.NonFatal

/**
 * Workspace-runner local regex safety helper used for user-supplied patterns.
 */
object WorkspaceRegexSafetyManager {

  private val MaxPatternLength            = 1000
  private val MaxInputLength              = 100000
  private val DefaultCompilationTimeoutMs = 1000L

  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]",
    "\\([^)]*[+*][^)]*\\)[+*]",
    "\\([^)]*\\|[^)]*\\)[+*]"
  ).map(_.r)

  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags, DefaultCompilationTimeoutMs)
    } yield p

  def safeFind(pattern: Pattern, input: String): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ =>
      try
        Right(pattern.matcher(input).find())
      catch {
        case NonFatal(e) => Left(s"Regex matching failed: ${e.getMessage}")
      }
    )

  def safeReplaceAll(pattern: Pattern, input: String, replacement: String): Either[String, String] =
    withInputValidation(input).flatMap(_ =>
      try
        Right(pattern.matcher(input).replaceAll(replacement))
      catch {
        case NonFatal(e) => Left(s"Regex replacement failed: ${e.getMessage}")
      }
    )

  def safeReplaceFirst(pattern: Pattern, input: String, replacement: String): Either[String, String] =
    withInputValidation(input).flatMap(_ =>
      try
        Right(pattern.matcher(input).replaceFirst(replacement))
      catch {
        case NonFatal(e) => Left(s"Regex replacement failed: ${e.getMessage}")
      }
    )

  def compileLiteral(pattern: String, flags: Int = 0): Pattern =
    Pattern.compile(Pattern.quote(pattern), flags)

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

  private def validatePattern(pattern: String): Either[String, Unit] =
    if (pattern == null) Left("Pattern cannot be null")
    else if (pattern.trim.isEmpty) Left("Pattern cannot be empty")
    else if (pattern.length > MaxPatternLength)
      Left(s"Pattern too long: ${pattern.length} > $MaxPatternLength")
    else if (DangerousPatternShapes.exists(_.findFirstIn(pattern).isDefined))
      Left("Regex pattern contains nested quantifiers/overlap and may cause ReDoS")
    else if (hasOverlappingAlternationWithQuantifier(pattern))
      Left("Regex pattern contains quantified overlapping alternation and may cause ReDoS")
    else Right(())

  private def withInputValidation(input: String): Either[String, Unit] =
    if (input == null) Left("Input cannot be null")
    else if (input.length > MaxInputLength)
      Left(s"Input too large: ${input.length} > $MaxInputLength")
    else Right(())

  private def compileSafely(pattern: String, flags: Int, timeoutMs: Long): Either[String, Pattern] =
    try {
      val _ = timeoutMs // preserved for API stability
      Right(Pattern.compile(pattern, flags))
    } catch {
      case e: PatternSyntaxException => Left(s"Invalid regex syntax: ${e.getMessage}")
      case NonFatal(e)               => Left(s"Regex compilation failed: ${e.getMessage}")
    }

  private def hasOverlappingAlternationWithQuantifier(pattern: String): Boolean = {
    val quantifiedAlt = "\\(([^)]*\\|[^)]*)\\)([+*])".r
    quantifiedAlt.findAllMatchIn(pattern).exists { m =>
      val alternatives = m.group(1).split("\\|").map(_.trim).filter(_.nonEmpty)
      alternatives.combinations(2).exists {
        case Array(a, b) => a.startsWith(b) || b.startsWith(a)
        case _           => false
      }
    }
  }
}
