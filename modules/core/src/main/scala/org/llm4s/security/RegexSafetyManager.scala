package org.llm4s.security

import org.llm4s.annotation.Stable

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.Try
import scala.util.control.NonFatal

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
@Stable
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

  /**
   * Compiles a user-supplied pattern after the pre-screen, without throwing.
   *
   * Returns `Left` with a message when the pattern is null, empty or blank, is longer than 1000 characters,
   * matches one of the known catastrophic-backtracking shapes (nested quantifiers, or a quantified alternation
   * whose alternatives overlap), or is not valid regex syntax. The pre-screen is only an early reject: bound
   * matching with [[safeFind]] or [[safeMatches]].
   *
   * @param pattern the regular expression to compile
   * @param flags `java.util.regex.Pattern` flags, such as `Pattern.CASE_INSENSITIVE`; 0 by default
   * @return the compiled `Pattern`, or `Left` with the reason it was rejected
   */
  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags)
    } yield p

  /**
   * Tells whether the pattern is found anywhere in the input (`Matcher.find`), within a step budget.
   *
   * Returns `Left` with a message when the input is null or longer than 100000 characters, when matching reads
   * more than `maxSteps` characters (likely catastrophic backtracking), when the pattern recurses too deeply for
   * the input, or when matching fails for another non-fatal reason. See [[safeMatches]] for whole-input matching.
   *
   * @param pattern the compiled pattern, e.g. from [[safeCompile]]
   * @param input the text to search
   * @param maxSteps the most character accesses one match may make; 10000000 by default
   * @return `Right(true)` if a match is found, `Right(false)` if not, or `Left` with the reason matching stopped
   */
  def safeFind(pattern: Pattern, input: String, maxSteps: Long = DefaultMaxMatchSteps): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => guardMatch(boundedMatcher(pattern, input, maxSteps).find()))

  /**
   * Tells whether the pattern matches the whole input (`Matcher.matches`), within a step budget.
   *
   * It is [[safeFind]] for the whole input: `find` succeeds on a match anywhere in the input, `matches` only
   * when the pattern matches all of it. The same `Left` cases apply: a null or over-long input (more than 100000
   * characters), an exceeded step budget, too deep a recursion, or another non-fatal failure.
   *
   * @param pattern the compiled pattern, e.g. from [[safeCompile]]
   * @param input the text to match
   * @param maxSteps the most character accesses one match may make; 10000000 by default
   * @return `Right(true)` if the whole input matches, `Right(false)` if not, or `Left` with the reason matching
   *         stopped
   */
  def safeMatches(pattern: Pattern, input: String, maxSteps: Long = DefaultMaxMatchSteps): Either[String, Boolean] =
    withInputValidation(input).flatMap(_ => guardMatch(boundedMatcher(pattern, input, maxSteps).matches()))

  /**
   * Compiles text as a literal pattern: it is quoted with `Pattern.quote`, so regex metacharacters in it match
   * themselves.
   *
   * It returns the `Pattern` directly and does not apply the checks of [[safeCompile]]. A quoted pattern has no
   * quantifiers or alternation, so the shapes those checks look for cannot occur in it.
   *
   * @param pattern the text to match literally
   * @param flags `java.util.regex.Pattern` flags; 0 by default
   * @return a `Pattern` that matches `pattern` as plain text
   */
  def compileLiteral(pattern: String, flags: Int = 0): Pattern =
    Pattern.compile(Pattern.quote(pattern), flags)

  /**
   * Compiles text as a literal pattern that ignores case (`Pattern.CASE_INSENSITIVE`).
   *
   * Like [[compileLiteral]], it quotes the text and does not apply the checks of [[safeCompile]].
   *
   * @param pattern the text to match literally
   * @return a case-insensitive `Pattern` that matches `pattern` as plain text
   */
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

  // The JDK engine recurses for some shapes, such as `(a|aa)*b`, and can overflow the stack on long input
  // before the step budget trips. `Try` does not catch that fatal error, so it is caught here: by the time
  // the handler runs the matcher's frames have unwound, so continuing on this thread is safe. Every other
  // fatal error (out of memory, `InterruptedException`, linkage errors) still propagates.
  // scalafix:off DisableSyntax.NoKeywordCatch
  private def guardMatch(matchOp: => Boolean): Either[String, Boolean] =
    try Right(matchOp)
    catch {
      case _: RegexComplexityException => Left("Regex matching aborted: exceeded complexity budget (possible ReDoS)")
      case _: StackOverflowError =>
        Left("Regex matching aborted: pattern recursed too deeply for the input (stack overflow)")
      case NonFatal(e) => Left(s"Regex matching failed: ${e.getMessage}")
    }
  // scalafix:on DisableSyntax.NoKeywordCatch

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
   * Replaces every occurrence of a literal text in the input.
   *
   * Both `literalPattern` and `replacement` are taken literally: the pattern is compiled with [[compileLiteral]]
   * and the replacement goes through `Matcher.quoteReplacement`, so `$` and `\` in it have no special meaning.
   *
   * @param input the text to search
   * @param literalPattern the text to find, matched literally
   * @param replacement the text to put in its place, inserted literally
   * @param caseInsensitive whether `literalPattern` matches regardless of case (`Pattern.CASE_INSENSITIVE`)
   * @return the input with every occurrence replaced
   */
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

  /**
   * Replaces the first occurrence of a literal text in the input.
   *
   * It is [[replaceAllLiteral]] for the first occurrence only: `literalPattern` and `replacement` are both taken
   * literally, the replacement through `Matcher.quoteReplacement`.
   *
   * @param input the text to search
   * @param literalPattern the text to find, matched literally
   * @param replacement the text to put in its place, inserted literally
   * @param caseInsensitive whether `literalPattern` matches regardless of case (`Pattern.CASE_INSENSITIVE`)
   * @return the input with its first occurrence replaced, or the input unchanged if there is none
   */
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
