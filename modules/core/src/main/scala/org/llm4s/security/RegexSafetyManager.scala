package org.llm4s.security

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

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
 *   2. A hard execution bound ([[safeFind]]) caps the number of
 *      character accesses the backtracking engine may perform for a single
 *      match, without relying on thread interruption or wall-clock timeouts.
 *
 * The step bound is the real security boundary, and [[safeCompile]]'s structural check is what makes it sound
 * (#3051): the bound counts reads, and the engine could otherwise do unbounded work without reading, in many required
 * iterations of a loop whose body matches the empty string (`(){2000000000}b`) or across many ways of matching the
 * empty string at the end of the input (`(?:|)(?:|)...b`). With every pattern refused that can take more than 1000
 * steps at one position without a read, the work between two reads is bounded. The shape blocklist is only a fast
 * early-reject and must not be relied on for safety.
 *
 * Every refusal is a `Left` of [[org.llm4s.error.ValidationError]], for the field `pattern` or `input`; nothing
 * here throws.
 *
 * NOTE: kept intentionally in sync with
 * `org.llm4s.runner.WorkspaceRegexSafetyManager`, which duplicates this logic
 * because the workspace runner module cannot depend on `core` (and so returns
 * `Either[String, _]`, not `Result`). Changes to the heuristics or bounds here
 * should be mirrored there.
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

  // Common catastrophic-backtracking shapes (cheap early-reject only). A quantified alternation, `(x|y)+`, is not
  // one of them (#2825): `(foo|bar)+` is an ordinary pattern, and only alternatives that overlap are refused, by
  // `hasOverlappingAlternationWithQuantifier`.
  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]", // ((x+)+)+ or ((x*)*)*
    "\\([^)]*[+*][^)]*\\)[+*]"                 // (x+)+, (x*)*, (x+)*
  ).map(_.r)

  /** Raised when a match exceeds its step budget (likely catastrophic backtracking); caught in `guardMatch`. */
  final private class RegexComplexityException(message: String) extends RuntimeException(message)

  /**
   * Compiles a user-supplied pattern after the pre-screen, without throwing.
   *
   * Returns a `Left` when the pattern is null, empty or blank, is longer than 1000 characters, matches one of the
   * known catastrophic-backtracking shapes (nested quantifiers such as `(a+)+`, or a quantified alternation whose
   * alternatives overlap, such as `(a|ab)*`), or is not valid regex syntax. A quantified alternation whose
   * alternatives do not overlap, such as `(foo|bar)+`, compiles. The pre-screen is only an early reject: bound
   * matching with [[safeFind]].
   *
   * It also returns a `Left` for a pattern whose matching the step bound of [[safeFind]] could not bound (#3051): one
   * that can take more than 1000 steps at one position without reading the input, such as many required iterations
   * of something that can match the empty string (`(){5000}`) or many ways in a row to match it (`(?:|)`,
   * `(\s*)?` or `(?:a?)*` repeated); and comments mode or canonical equivalence (`(?x)`, `Pattern.COMMENTS`,
   * `Pattern.CANON_EQ`), whose syntax the check does not follow. One empty-matching part, such as `(\s*)?`, `(a|)?`
   * or `(a?)*`, or a few in a row, compiles.
   *
   * @param pattern the regular expression to compile
   * @param flags `java.util.regex.Pattern` flags, such as `Pattern.CASE_INSENSITIVE`; 0 by default
   * @return the compiled `Pattern`, or a [[org.llm4s.error.ValidationError]] for the field `pattern` whose message
   *         holds the reason it was rejected
   */
  def safeCompile(pattern: String, flags: Int = 0): Result[Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags)
      // no repetition limit here, unlike the workspace runner's: a counted repetition over a body that reads costs
      // reads, which the budget counts, and a schema's `.{0,4096}` is ordinary
      _ <- RegexShape.refusal(pattern, flags, Int.MaxValue).fold[Result[Unit]](Right(()))(refusedPattern)
    } yield p

  /**
   * Tells whether the pattern is found anywhere in the input (`Matcher.find`), within a budget of 10000000
   * character accesses.
   *
   * Returns a `Left` when the input is null or longer than 100000 characters, when matching exceeds the budget
   * (likely catastrophic backtracking), when the pattern recurses too deeply for the input, or when matching fails
   * for another non-fatal reason.
   *
   * @param pattern the compiled pattern, e.g. from [[safeCompile]]
   * @param input the text to search
   * @return `Right(true)` if a match is found, `Right(false)` if not, or a [[org.llm4s.error.ValidationError]]
   *         whose message holds the reason matching stopped: for the field `input` when the input was refused, for
   *         `pattern` when the match was
   */
  def safeFind(pattern: Pattern, input: String): Result[Boolean] =
    safeFind(pattern, input, DefaultMaxMatchSteps)

  /** [[safeFind]] with a step budget of the caller's, for tests of the bound. */
  private[llm4s] def safeFind(pattern: Pattern, input: String, maxSteps: Long): Result[Boolean] =
    withInputValidation(input).flatMap(_ => guardMatch(boundedMatcher(pattern, input, maxSteps).find()))

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

  private def refusedPattern[A](reason: String): Result[A] = Left(ValidationError("pattern", reason))
  private def refusedInput[A](reason: String): Result[A]   = Left(ValidationError("input", reason))

  private def validatePattern(pattern: String): Result[Unit] =
    if (pattern == null) refusedPattern("Pattern cannot be null")
    else if (pattern.trim.isEmpty) refusedPattern("Pattern cannot be empty")
    else if (pattern.length > MaxPatternLength)
      refusedPattern(s"Pattern too long: ${pattern.length} > $MaxPatternLength")
    else if (DangerousPatternShapes.exists(_.findFirstIn(pattern).isDefined))
      refusedPattern("Regex pattern contains nested quantifiers and may cause ReDoS")
    else if (hasOverlappingAlternationWithQuantifier(pattern))
      refusedPattern("Regex pattern contains quantified overlapping alternation and may cause ReDoS")
    else Right(())

  private def withInputValidation(input: String): Result[Unit] =
    if (input == null) refusedInput("Input cannot be null")
    else if (input.length > MaxInputLength)
      refusedInput(s"Input too large: ${input.length} > $MaxInputLength")
    else Right(())

  private def compileSafely(pattern: String, flags: Int): Result[Pattern] =
    Try(Pattern.compile(pattern, flags)).toEither.left.map {
      case e: PatternSyntaxException => ValidationError("pattern", s"Invalid regex syntax: ${e.getMessage}")
      case e                         => ValidationError("pattern", s"Regex compilation failed: ${e.getMessage}")
    }

  private def boundedMatcher(pattern: Pattern, input: String, maxSteps: Long): Matcher =
    pattern.matcher(new StepBoundedCharSequence(input, maxSteps))

  // The JDK engine recurses for some shapes, such as `(a|aa)*b`, and can overflow the stack on long input
  // before the step budget trips. `Try` does not catch that fatal error, so it is caught here: by the time
  // the handler runs the matcher's frames have unwound, so continuing on this thread is safe. Every other
  // fatal error (out of memory, `InterruptedException`, linkage errors) still propagates.
  // scalafix:off DisableSyntax.NoKeywordCatch
  private def guardMatch(matchOp: => Boolean): Result[Boolean] =
    try Right(matchOp)
    catch {
      case _: RegexComplexityException =>
        refusedPattern("Regex matching aborted: exceeded complexity budget (possible ReDoS)")
      case _: StackOverflowError =>
        refusedPattern("Regex matching aborted: pattern recursed too deeply for the input (stack overflow)")
      case NonFatal(e) => refusedPattern(s"Regex matching failed: ${e.getMessage}")
    }
  // scalafix:on DisableSyntax.NoKeywordCatch

  private def hasOverlappingAlternationWithQuantifier(pattern: String): Boolean = {
    val quantifiedAlt = "\\(([^)]*\\|[^)]*)\\)([+*])".r
    quantifiedAlt.findAllMatchIn(pattern).exists { m =>
      // without the `?:` or `?<name>` that opens a non-capturing or named group, which is not part of an alternative
      val body         = m.group(1).replaceFirst("^\\?(?::|<[A-Za-z][A-Za-z0-9]*>)", "")
      val alternatives = body.split("\\|").map(_.trim).filter(_.nonEmpty)
      alternatives.combinations(2).exists {
        case Array(a, b) => a.startsWith(b) || b.startsWith(a)
        case _           => false
      }
    }
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
