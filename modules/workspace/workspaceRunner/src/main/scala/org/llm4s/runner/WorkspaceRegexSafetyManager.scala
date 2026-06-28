package org.llm4s.runner

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.Try

/**
 * Workspace-runner local regex safety helper for user-supplied patterns.
 *
 * ReDoS is mitigated by two complementary defences:
 *
 *   1. A cheap pre-screen ([[safeCompile]]) rejects null/empty/oversized
 *      patterns and well-known catastrophic-backtracking shapes before they are
 *      compiled.
 *   2. A hard execution bound ([[safeFind]]/[[safeReplaceAll]]/[[safeReplaceFirst]])
 *      caps the number of character accesses the backtracking engine may perform
 *      for a single operation. A step ceiling deterministically bounds worst-case
 *      runtime even for dangerous patterns the pre-screen misses, without relying
 *      on thread interruption or wall-clock timeouts.
 *
 * The step bound is the real security boundary; the shape blocklist is only a
 * fast early-reject.
 *
 * NOTE: kept intentionally in sync with `org.llm4s.security.RegexSafetyManager`
 * in `core`. This module cannot depend on `core`, so the logic is duplicated;
 * changes to the heuristics or bounds in either place should be mirrored.
 */
object WorkspaceRegexSafetyManager {

  private val MaxPatternLength = 1000
  private val MaxInputLength   = 100000

  /**
   * Upper bound on character accesses for a single match/replace. Comfortably
   * above legitimate matching for inputs up to [[MaxInputLength]], but far below
   * the exponential blow-up of catastrophic backtracking.
   */
  private val DefaultMaxMatchSteps = 10000000L

  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]",
    "\\([^)]*[+*][^)]*\\)[+*]",
    "\\([^)]*\\|[^)]*\\)[+*]"
  ).map(_.r)

  /** Raised when an operation exceeds its step budget (likely catastrophic backtracking). */
  final class RegexComplexityException(message: String) extends RuntimeException(message)

  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags)
    } yield p

  def safeFind(pattern: Pattern, input: String, maxSteps: Long = DefaultMaxMatchSteps): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => guard(boundedMatcher(pattern, input, maxSteps).find()))

  def safeReplaceAll(
    pattern: Pattern,
    input: String,
    replacement: String,
    maxSteps: Long = DefaultMaxMatchSteps
  ): Either[String, String] =
    withInputValidation(input).flatMap(_ => guard(boundedMatcher(pattern, input, maxSteps).replaceAll(replacement)))

  def safeReplaceFirst(
    pattern: Pattern,
    input: String,
    replacement: String,
    maxSteps: Long = DefaultMaxMatchSteps
  ): Either[String, String] =
    withInputValidation(input).flatMap(_ => guard(boundedMatcher(pattern, input, maxSteps).replaceFirst(replacement)))

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

  private def compileSafely(pattern: String, flags: Int): Either[String, Pattern] =
    Try(Pattern.compile(pattern, flags)).toEither.left.map {
      case e: PatternSyntaxException => s"Invalid regex syntax: ${e.getMessage}"
      case e                         => s"Regex compilation failed: ${e.getMessage}"
    }

  private def boundedMatcher(pattern: Pattern, input: String, maxSteps: Long): Matcher =
    pattern.matcher(new StepBoundedCharSequence(input, maxSteps))

  private def guard[A](op: => A): Either[String, A] =
    Try(op).toEither.left.map {
      case _: RegexComplexityException => "Regex operation aborted: exceeded complexity budget (possible ReDoS)"
      case e                           => s"Regex operation failed: ${e.getMessage}"
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

  /**
   * A [[CharSequence]] that counts character accesses and aborts once the step
   * budget is exhausted, giving the backtracking engine a hard ceiling. The
   * counter is shared with any subsequences the engine derives.
   */
  final private class StepBoundedCharSequence(
    inner: CharSequence,
    maxSteps: Long,
    counter: Array[Long]
  ) extends CharSequence {

    def this(inner: CharSequence, maxSteps: Long) = this(inner, maxSteps, Array(0L))

    override def length(): Int = inner.length()

    override def charAt(index: Int): Char = {
      counter(0) += 1L
      if (counter(0) > maxSteps)
        throw new RegexComplexityException(s"Regex operation exceeded step budget of $maxSteps")
      inner.charAt(index)
    }

    override def subSequence(start: Int, end: Int): CharSequence =
      new StepBoundedCharSequence(inner.subSequence(start, end), maxSteps, counter)

    override def toString: String = inner.toString
  }
}
