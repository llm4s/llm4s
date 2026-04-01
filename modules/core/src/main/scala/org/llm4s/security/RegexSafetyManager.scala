package org.llm4s.security

import java.util.concurrent.{ Executors, TimeUnit, TimeoutException }
import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }

/**
 * Safety wrapper for user-supplied regex compilation and matching.
 *
 * This manager blocks known catastrophic patterns, enforces basic bounds,
 * and time-boxes compile/match operations to reduce ReDoS risk.
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
      p <- compileWithTimeout(pattern, flags, timeoutMs)
    } yield p

  def safeFind(pattern: Pattern, input: String, timeoutMs: Long = DefaultMatchingTimeoutMs): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => findWithTimeout(pattern, input, timeoutMs))

  def safeMatches(
    pattern: Pattern,
    input: String,
    timeoutMs: Long = DefaultMatchingTimeoutMs
  ): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => matchesWithTimeout(pattern, input, timeoutMs))

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

  private def compileWithTimeout(pattern: String, flags: Int, timeoutMs: Long): Either[String, Pattern] = {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit(new java.util.concurrent.Callable[Pattern] {
        override def call(): Pattern = Pattern.compile(pattern, flags)
      })
      Right(future.get(timeoutMs, TimeUnit.MILLISECONDS))
    } catch {
      case _: TimeoutException       => Left(s"Regex compilation timeout after ${timeoutMs}ms")
      case e: PatternSyntaxException => Left(s"Invalid regex syntax: ${e.getMessage}")
      case e: Exception              => Left(s"Regex compilation failed: ${e.getMessage}")
    } finally executor.shutdownNow()
  }

  private def findWithTimeout(pattern: Pattern, input: String, timeoutMs: Long): Either[String, Boolean] = {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit(new java.util.concurrent.Callable[Boolean] {
        override def call(): Boolean = pattern.matcher(input).find()
      })
      Right(future.get(timeoutMs, TimeUnit.MILLISECONDS))
    } catch {
      case _: TimeoutException => Left(s"Regex matching timeout after ${timeoutMs}ms")
      case e: Exception        => Left(s"Regex matching failed: ${e.getMessage}")
    } finally executor.shutdownNow()
  }

  private def matchesWithTimeout(pattern: Pattern, input: String, timeoutMs: Long): Either[String, Boolean] = {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit(new java.util.concurrent.Callable[Boolean] {
        override def call(): Boolean = pattern.matcher(input).matches()
      })
      Right(future.get(timeoutMs, TimeUnit.MILLISECONDS))
    } catch {
      case _: TimeoutException => Left(s"Regex matching timeout after ${timeoutMs}ms")
      case e: Exception        => Left(s"Regex matching failed: ${e.getMessage}")
    } finally executor.shutdownNow()
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
