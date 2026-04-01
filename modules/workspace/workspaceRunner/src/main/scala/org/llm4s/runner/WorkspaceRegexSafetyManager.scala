package org.llm4s.runner

import java.util.concurrent.{ Executors, TimeUnit, TimeoutException }
import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }

/**
 * Workspace-runner local regex safety helper used for user-supplied patterns.
 */
object WorkspaceRegexSafetyManager {

  private val MaxPatternLength            = 1000
  private val DefaultCompilationTimeoutMs = 1000L

  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]",
    "\\([^)]*[+*][^)]*\\)[+*]",
    "\\([^)]*\\|[^)]*\\)\\*"
  ).map(_.r)

  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileWithTimeout(pattern, flags, DefaultCompilationTimeoutMs)
    } yield p

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
}
