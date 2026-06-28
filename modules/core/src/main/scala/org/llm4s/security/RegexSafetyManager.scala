package org.llm4s.security

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.Try

/**
 * Safety wrapper for user-supplied regex compilation and matching.
 *
 * ReDoS is mitigated by two complementary defences:
 *
 *   1. A cheap pre-screen ([[safeCompile]]) rejects null/empty/oversized
 *      patterns and a set of well-known catastrophic-backtracking shapes before
 *      they are ever compiled.
 *   2. A hard execution bound ([[safeFind]]/[[safeMatches]]) caps the number of
 *      character accesses the backtracking engine may perform for a single
 *      match. Catastrophic backtracking explores an exponential number of paths,
 *      each of which reads characters, so a step ceiling deterministically
 *      bounds worst-case runtime - even for dangerous patterns the pre-screen
 *      misses - without relying on thread interruption or wall-clock timeouts.
 *
 * The step bound is the real security boundary: any pattern that reaches the
 * matching helpers is safe regardless of how it was constructed. The shape
 * blocklist is only a fast early-reject and must not be relied on for safety.
 *
 * NOTE: kept intentionally in sync with
 * `org.llm4s.runner.WorkspaceRegexSafetyManager`, which duplicates this logic
 * because the workspace runner module cannot depend on `core`. Changes to the
 * heuristics or bounds here should be mirrored there.
 */
object RegexSafetyManager {

  private val MaxPatternLength = 1000
  private val MaxInputLength   = 100000

  /**
   * Upper bound on character accesses for a single match. Comfortably above
   * what legitimate linear/quadratic matching needs for inputs up to
   * [[MaxInputLength]], but far below the exponential blow-up of catastrophic
   * backtracking, which trips the ceiling almost immediately.
   */
  private val DefaultMaxMatchSteps = 10000000L

  private val DefaultCaseInsensitiveFlags = Pattern.CASE_INSENSITIVE

  // Common catastrophic-backtracking shapes (cheap early-reject only).
  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]", // ((x+)+)+ or ((x*)*)*
    "\\([^)]*[+*][^)]*\\)[+*]",                // (x+)+, (x*)*, (x+)*
    "\\([^)]*\\|[^)]*\\)[+*]"                  // (x|y)*, (x|y)+
  ).map(_.r)

  /** Raised when a match exceeds its step budget (likely catastrophic backtracking). */
  final class RegexComplexityException(message: String) extends RuntimeException(message)

  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags)
    } yield p

  def safeFind(pattern: Pattern, input: String, maxSteps: Long = DefaultMaxMatchSteps): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => guardMatch(boundedMatcher(pattern, input, maxSteps).find()))

  def safeMatches(pattern: Pattern, input: String, maxSteps: Long = DefaultMaxMatchSteps): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => guardMatch(boundedMatcher(pattern, input, maxSteps).matches()))

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

  private def guardMatch(matchOp: => Boolean): Either[String, Boolean] =
    Try(matchOp).toEither.left.map {
      case _: RegexComplexityException => "Regex matching aborted: exceeded complexity budget (possible ReDoS)"
      case e                           => s"Regex matching failed: ${e.getMessage}"
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

  /**
   * A [[CharSequence]] that counts character accesses and aborts once the step
   * budget is exhausted, giving the backtracking engine a hard ceiling. The
   * counter is shared with any subsequences the engine derives so the bound
   * cannot be reset mid-match.
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
        throw new RegexComplexityException(s"Regex matching exceeded step budget of $maxSteps")
      inner.charAt(index)
    }

    override def subSequence(start: Int, end: Int): CharSequence =
      new StepBoundedCharSequence(inner.subSequence(start, end), maxSteps, counter)

    override def toString: String = inner.toString
  }
}
