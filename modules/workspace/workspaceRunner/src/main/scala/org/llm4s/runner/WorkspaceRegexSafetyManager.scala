package org.llm4s.runner

import java.util.regex.{ Matcher, Pattern, PatternSyntaxException }
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Workspace-runner local regex safety helper for user-supplied patterns.
 *
 * ReDoS is mitigated by three complementary defences:
 *
 *   1. A pre-screen ([[safeCompile]]) rejects null/empty/oversized patterns, well-known catastrophic-backtracking
 *      shapes, and every pattern [[RegexShape]] cannot prove bounded: a quantifier over something that can match the
 *      empty string, an alternation with more than one such alternative, or a counted repetition above
 *      [[MaxRepetition]] (#3051).
 *   2. A hard execution bound ([[safeFind]]/[[safeReplaceAll]]/[[safeReplaceFirst]]) caps the number of character
 *      accesses the backtracking engine may perform for a single operation. [[RegexShape]] is what makes this sound:
 *      with its shapes refused, the engine cannot do unbounded work between two reads.
 *   3. An optional wall-clock deadline, checked as the engine reads, so a request's matching across many lines ends
 *      on time however the per-line budgets add up.
 *
 * The shape blocklist is only a fast early-reject.
 *
 * NOTE: kept intentionally in sync with `org.llm4s.security.RegexSafetyManager`
 * in `core`. This module cannot depend on `core`, so the logic is duplicated;
 * changes to the heuristics or bounds in either place should be mirrored.
 */
object WorkspaceRegexSafetyManager {

  private val MaxPatternLength = 1000
  private val MaxInputLength   = 100000

  /** The largest count a counted repetition (`{n}`, `{n,m}`) may give. */
  val MaxRepetition = 1000

  /**
   * Upper bound on character accesses for a single match/replace. Comfortably
   * above legitimate matching for inputs up to [[MaxInputLength]], but far below
   * the exponential blow-up of catastrophic backtracking.
   */
  private val DefaultMaxMatchSteps = 10000000L

  /** How many reads pass between two looks at the deadline. */
  private val DeadlineCheckMask = 0x3ffL

  private val DangerousPatternShapes = List(
    "\\(\\([^)]*[+*][^)]*\\)[+*][^)]*\\)[+*]",
    "\\([^)]*[+*][^)]*\\)[+*]"
  ).map(_.r)

  /** The `Left` of an operation stopped at its deadline. */
  val TimeLimitReached = "Regex operation aborted: the request's time limit was reached"

  /** The `Left` of a replacement stopped because its result outgrew `maxOutputChars`. */
  val OutputLimitReached = "Regex replacement aborted: the result is larger than the limit"

  /** Raised when an operation exceeds its step budget (likely catastrophic backtracking). */
  final class RegexComplexityException(message: String) extends RuntimeException(message)

  final private class DeadlineException       extends RuntimeException(TimeLimitReached, null, false, false)
  final private class OutputTooLargeException extends RuntimeException(OutputLimitReached, null, false, false)

  def safeCompile(pattern: String, flags: Int = 0): Either[String, Pattern] =
    for {
      _ <- validatePattern(pattern)
      p <- compileSafely(pattern, flags)
      _ <- RegexShape.refusal(pattern, flags, MaxRepetition).toLeft(())
    } yield p

  /**
   * Whether `pattern` is found in `input`, within `maxSteps` reads and, given a `deadline` (a `System.nanoTime`
   * value), by then.
   */
  def safeFind(
    pattern: Pattern,
    input: String,
    maxSteps: Long = DefaultMaxMatchSteps,
    deadline: Option[Long] = None
  ): Either[String, Boolean] =
    safeFindStart(pattern, input, maxSteps, deadline).map(_.isDefined)

  /** As [[safeFind]], with where the first match starts. */
  def safeFindStart(
    pattern: Pattern,
    input: String,
    maxSteps: Long = DefaultMaxMatchSteps,
    deadline: Option[Long] = None
  ): Either[String, Option[Int]] =
    withInputValidation(input).flatMap { _ =>
      guard {
        val m = boundedMatcher(pattern, input, maxSteps, deadline)
        if (m.find()) Some(m.start()) else None
      }
    }

  /** Every match replaced, within the bounds of [[safeFind]] and a result of at most `maxOutputChars` characters. */
  def safeReplaceAll(
    pattern: Pattern,
    input: String,
    replacement: String,
    maxSteps: Long = DefaultMaxMatchSteps,
    deadline: Option[Long] = None,
    maxOutputChars: Long = Long.MaxValue
  ): Either[String, String] =
    withInputValidation(input).flatMap { _ =>
      guard(bounded(boundedMatcher(pattern, input, maxSteps, deadline), replacement, global = true, maxOutputChars))
    }

  /** The first match replaced, within the bounds of [[safeReplaceAll]]. */
  def safeReplaceFirst(
    pattern: Pattern,
    input: String,
    replacement: String,
    maxSteps: Long = DefaultMaxMatchSteps,
    deadline: Option[Long] = None,
    maxOutputChars: Long = Long.MaxValue
  ): Either[String, String] =
    withInputValidation(input).flatMap { _ =>
      guard(bounded(boundedMatcher(pattern, input, maxSteps, deadline), replacement, global = false, maxOutputChars))
    }

  def compileLiteral(pattern: String, flags: Int = 0): Pattern =
    Pattern.compile(Pattern.quote(pattern), flags)

  /**
   * `literalPattern` replaced as plain text: every occurrence, or with `global = false` the first, in a result of at
   * most `maxOutputChars` characters ([[OutputLimitReached]] otherwise).
   */
  def replaceLiteral(
    input: String,
    literalPattern: String,
    replacement: String,
    caseInsensitive: Boolean,
    global: Boolean,
    maxOutputChars: Long = Long.MaxValue
  ): Either[String, String] = {
    val flags   = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
    val matcher = compileLiteral(literalPattern, flags).matcher(input)
    guard(bounded(matcher, Matcher.quoteReplacement(replacement), global, maxOutputChars))
  }

  /** `Matcher.replaceAll` / `replaceFirst`, stopped once the result outgrows `maxOutputChars`. */
  private def bounded(m: Matcher, replacement: String, global: Boolean, maxOutputChars: Long): String = {
    val out   = new java.lang.StringBuilder
    var found = m.find()
    while (found) {
      // one replacement adds the gap before it (at most the input), the replacement text and the groups it copies,
      // which are read through the step-counted input: so checking as each is appended bounds what is built
      m.appendReplacement(out, replacement)
      if (out.length.toLong > maxOutputChars) throw new OutputTooLargeException
      found = global && m.find()
    }
    m.appendTail(out)
    if (out.length.toLong > maxOutputChars) throw new OutputTooLargeException
    out.toString
  }

  private def validatePattern(pattern: String): Either[String, Unit] =
    if (pattern == null) Left("Pattern cannot be null")
    else if (pattern.trim.isEmpty) Left("Pattern cannot be empty")
    else if (pattern.length > MaxPatternLength)
      Left(s"Pattern too long: ${pattern.length} > $MaxPatternLength")
    else if (DangerousPatternShapes.exists(_.findFirstIn(pattern).isDefined))
      Left("Regex pattern contains nested quantifiers and may cause ReDoS")
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

  private def boundedMatcher(pattern: Pattern, input: String, maxSteps: Long, deadline: Option[Long]): Matcher =
    pattern.matcher(new StepBoundedCharSequence(input, maxSteps, deadline.isDefined, deadline.getOrElse(0L)))

  // Mirrors `RegexSafetyManager.guardMatch`: a recursive pattern such as `(a|aa)*b` can overflow the stack on
  // long input before the step budget trips, and `Try` does not catch that fatal error. The stack has unwound
  // by the time the handler runs, so it is safe to continue; every other fatal error still propagates.
  // scalafix:off DisableSyntax.NoKeywordCatch
  private def guard[A](op: => A): Either[String, A] =
    try Right(op)
    catch {
      case _: RegexComplexityException => Left("Regex operation aborted: exceeded complexity budget (possible ReDoS)")
      case _: DeadlineException        => Left(TimeLimitReached)
      case _: OutputTooLargeException  => Left(OutputLimitReached)
      case _: StackOverflowError =>
        Left("Regex operation aborted: pattern recursed too deeply for the input (stack overflow)")
      case NonFatal(e) => Left(s"Regex operation failed: ${e.getMessage}")
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
   * budget is exhausted, giving the backtracking engine a hard ceiling, and, when `timed`, once `System.nanoTime`
   * passes `deadline`. The counter is shared with any subsequences the engine derives.
   */
  final private class StepBoundedCharSequence(
    inner: CharSequence,
    maxSteps: Long,
    timed: Boolean,
    deadline: Long,
    counter: Array[Long]
  ) extends CharSequence {

    def this(inner: CharSequence, maxSteps: Long, timed: Boolean, deadline: Long) =
      this(inner, maxSteps, timed, deadline, Array(0L))

    override def length(): Int = inner.length()

    override def charAt(index: Int): Char = {
      val steps = counter(0) + 1L
      counter(0) = steps
      if (steps > maxSteps)
        throw new RegexComplexityException(s"Regex operation exceeded step budget of $maxSteps")
      if (timed && (steps & DeadlineCheckMask) == 0L && System.nanoTime() - deadline > 0L)
        throw new DeadlineException
      inner.charAt(index)
    }

    override def subSequence(start: Int, end: Int): CharSequence =
      new StepBoundedCharSequence(inner.subSequence(start, end), maxSteps, timed, deadline, counter)

    override def toString: String = inner.toString
  }
}
