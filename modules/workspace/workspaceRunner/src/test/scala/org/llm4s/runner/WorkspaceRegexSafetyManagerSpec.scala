package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.regex.Pattern

class WorkspaceRegexSafetyManagerSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val recursivePattern = Pattern.compile("(a|aa)*b")
  private val recursiveInput   = "a" * 50000
  private val overflow   = Left("Regex operation aborted: pattern recursed too deeply for the input (stack overflow)")
  private val complexity = Left("Regex operation aborted: exceeded complexity budget (possible ReDoS)")

  // Runs `body` on a thread with a small, fixed stack, so a recursive match overflows whatever -Xss the test JVM
  // was given. An error that escaped the guard reaches the uncaught-exception handler and fails the test.
  private def onSmallStack[A](body: => A): A = {
    @volatile var result: Option[A]          = None
    @volatile var escaped: Option[Throwable] = None
    val thread = new Thread(null, () => result = Some(body), "small-stack-regex", 256L * 1024)
    thread.setUncaughtExceptionHandler((_, e) => escaped = Some(e))
    thread.start()
    thread.join(30000)
    thread.isAlive shouldBe false
    escaped.foreach(e => fail(s"${e.getClass.getName} escaped the regex guard", e))
    result.getOrElse(fail("the operation produced no result"))
  }

  "WorkspaceRegexSafetyManager.safeCompile" should "accept a quantified alternation whose alternatives do not overlap (#2825)" in {
    Seq("(foo|bar)+", "(?:jpg|png)*", "^(GET|POST)+$", "(?<verb>GET|POST)+ /").foreach { pattern =>
      withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isRight shouldBe true)
    }
  }

  it should "still reject a quantified alternation whose alternatives overlap, and nested quantifiers" in {
    Seq("(a|a)*b", "(a|aa)+", "(ab|a)*c", "(?:a|ab)*c", "(?<x>a|ab)+", "(a+)+b", "((a+)+)+b").foreach { pattern =>
      withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isLeft shouldBe true)
    }
  }

  "WorkspaceRegexSafetyManager" should "find and replace normally within the budget" in {
    val p = Pattern.compile("b+")
    WorkspaceRegexSafetyManager.safeFind(p, "aabbb") shouldBe Right(true)
    WorkspaceRegexSafetyManager.safeReplaceAll(p, "abba bb", "X") shouldBe Right("aXa X")
    WorkspaceRegexSafetyManager.safeReplaceFirst(p, "abba bb", "X") shouldBe Right("aXa bb")
  }

  it should "return a Left when an operation exceeds its step budget" in {
    val p = Pattern.compile("(a+)+c")
    val s = "a" * 30 + "b"
    WorkspaceRegexSafetyManager.safeFind(p, s, maxSteps = 1000L) shouldBe complexity
    WorkspaceRegexSafetyManager.safeReplaceAll(p, s, "X", maxSteps = 1000L) shouldBe complexity
    WorkspaceRegexSafetyManager.safeReplaceFirst(p, s, "X", maxSteps = 1000L) shouldBe complexity
  }

  it should "return a Left when a recursive pattern overflows the stack" in {
    onSmallStack(WorkspaceRegexSafetyManager.safeFind(recursivePattern, recursiveInput)) shouldBe overflow
    onSmallStack(WorkspaceRegexSafetyManager.safeReplaceAll(recursivePattern, recursiveInput, "X")) shouldBe overflow
    onSmallStack(WorkspaceRegexSafetyManager.safeReplaceFirst(recursivePattern, recursiveInput, "X")) shouldBe overflow
  }

  it should "keep working on the same thread after a stack overflow" in {
    onSmallStack {
      WorkspaceRegexSafetyManager.safeFind(recursivePattern, recursiveInput)
      WorkspaceRegexSafetyManager.safeReplaceAll(recursivePattern, "aab-ab", "X")
    } shouldBe Right("X-X")
  }

  it should "return a Left when an operation fails with any other non-fatal exception" in {
    // A replacement naming a group the pattern does not have makes the matcher throw IndexOutOfBoundsException.
    val missingGroup = "$" + "9"
    val all          = WorkspaceRegexSafetyManager.safeReplaceAll(Pattern.compile("a"), "abc", missingGroup)
    all.isLeft shouldBe true
    all.left.toOption.get should startWith("Regex operation failed:")

    val first = WorkspaceRegexSafetyManager.safeReplaceFirst(Pattern.compile("a"), "abc", missingGroup)
    first.left.toOption.get should startWith("Regex operation failed:")

    WorkspaceRegexSafetyManager.safeFind(null, "abc").left.toOption.get should startWith("Regex operation failed:")
  }

  it should "reject null and oversized input before matching" in {
    WorkspaceRegexSafetyManager.safeFind(Pattern.compile("a"), null) shouldBe Left("Input cannot be null")
    WorkspaceRegexSafetyManager.safeReplaceAll(Pattern.compile("a"), "a" * 100001, "X").isLeft shouldBe true
  }

  // #3051: the step budget counts reads, so a loop whose body matches the empty string ran unbounded under it:
  // `(){2000000000}b` took 18 s on a 40-character line with 40 reads counted.
  "WorkspaceRegexSafetyManager.safeCompile" should "refuse a pattern that can take many steps without reading" in {
    Seq(
      "(){2000000000}b", // over the count cap, and a required count over an empty body
      "(){1000}b",       // within the cap, but 1000 required iterations that read nothing
      "(?:(){100}){100}",
      // on an empty line every one of 2^n empty paths is tried without a single read
      "(?:|)" * 40 + "b",
      "(?:(?=x)|)" * 30 + "b",
      "(?:a?)*" * 20 + "b",
      "(?:a?){0,40}b",
      "(a?)\\Q\\E{0,40}" // `\Q\E` is removed before parsing, so this is `(a?){0,40}`
    ).foreach(pattern => withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isLeft shouldBe true))
  }

  it should "accept a repetition or alternation of something that can match the empty string, when it is bounded" in {
    // a `?`, a small count or one unbounded loop over a body that matches empty takes a few steps without reading
    Seq(
      "(\\s*)?",
      "(a|)?",
      "^(\\s*,\\s*)?$",
      "(a?)*b",
      "(?:a|){2,}b",
      "(a?){3}b",
      "\\b+",
      "(a?)\\1+",
      "(|x|)",
      "(a?|b?)c",
      "(?:a?)?" * 30 + "b"
    ).foreach(pattern => withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isRight shouldBe true))
  }

  it should "refuse a counted repetition above the limit, comments mode and canonical equivalence" in {
    WorkspaceRegexSafetyManager.safeCompile("a{1001}").isLeft shouldBe true
    WorkspaceRegexSafetyManager.safeCompile("a{2,1001}").isLeft shouldBe true
    WorkspaceRegexSafetyManager.safeCompile("(?:ab){1000}").isRight shouldBe true
    WorkspaceRegexSafetyManager.safeCompile("(?x) ( a ? ) *").isLeft shouldBe true
    WorkspaceRegexSafetyManager.safeCompile("a", Pattern.COMMENTS).isLeft shouldBe true
    WorkspaceRegexSafetyManager.safeCompile("a", Pattern.CANON_EQ).isLeft shouldBe true
  }

  it should "accept ordinary patterns" in {
    Seq(
      "b+",
      "^\\s*$",
      "\\d{4}-\\d{2}-\\d{2}",
      "(?:, \\w)*",
      "(foo|bar)?baz",
      "(a|)b",
      "def\\s+(\\w+)\\(",
      "[]a]+",
      "[^]a]*x",
      "[a-z&&[^q]]+",
      "(\\w)\\1+",
      "(?<word>\\w+) \\k<word>",
      "\\Q(a?)*\\E",
      "(?i)todo:?",
      "(?i:fix)me",
      "(?=\\w)\\w+",
      "(?<!\\\\)\"",
      "\\p{Lu}\\p{Ll}*",
      "\\x{1F600}+",
      "[^/]*\\.scala",
      ".*Spec\\.scala",
      "x{0,1000}",
      "a*+b",
      "\\R+",
      "(.*a){25}b"
    ).foreach { pattern =>
      withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).map(_.pattern()) shouldBe Right(pattern))
    }
  }

  "WorkspaceRegexSafetyManager.safeFind" should "stop at its deadline" in {
    val past = Some(System.nanoTime() - 1L)
    WorkspaceRegexSafetyManager.safeFind(Pattern.compile("b"), "a" * 5000, deadline = past) shouldBe
      Left(WorkspaceRegexSafetyManager.TimeLimitReached)
    WorkspaceRegexSafetyManager.safeReplaceAll(Pattern.compile("b"), "a" * 5000, "X", deadline = past) shouldBe
      Left(WorkspaceRegexSafetyManager.TimeLimitReached)
    // a deadline still ahead changes nothing
    WorkspaceRegexSafetyManager.safeFind(
      Pattern.compile("b"),
      "a" * 5000 + "b",
      deadline = Some(System.nanoTime() + 60L * 1000000000L)
    ) shouldBe Right(true)
  }

  it should "say where the first match starts" in {
    WorkspaceRegexSafetyManager.safeFindStart(Pattern.compile("b+"), "aabbb") shouldBe Right(Some(2))
    WorkspaceRegexSafetyManager.safeFindStart(Pattern.compile("c"), "aabbb") shouldBe Right(None)
  }

  "WorkspaceRegexSafetyManager replacement" should "stop once its result outgrows the limit" in {
    val limit = Left(WorkspaceRegexSafetyManager.OutputLimitReached)
    // an empty match at every position: 2001 insertions of 1000 characters
    WorkspaceRegexSafetyManager.safeReplaceAll(
      Pattern.compile("x*"),
      "a" * 2000,
      "R" * 1000,
      maxOutputChars = 10000L
    ) shouldBe limit
    WorkspaceRegexSafetyManager.safeReplaceAll(
      Pattern.compile("(a+)"),
      "a" * 2000,
      "$1$1",
      maxOutputChars = 3000L
    ) shouldBe limit
    WorkspaceRegexSafetyManager.replaceLiteral(
      "a" * 2000,
      "a",
      "RR",
      false,
      global = true,
      maxOutputChars = 3000L
    ) shouldBe limit
    // within the limit, the same as Matcher's
    WorkspaceRegexSafetyManager.safeReplaceAll(Pattern.compile("x*"), "ab", "-", maxOutputChars = 5L) shouldBe
      Right("-a-b-")
    WorkspaceRegexSafetyManager.safeReplaceFirst(Pattern.compile("(b)"), "abab", "[$1]") shouldBe Right("a[b]ab")
    WorkspaceRegexSafetyManager.replaceLiteral("a.a", ".", "$1", true, global = true) shouldBe Right("a$1a")
    WorkspaceRegexSafetyManager.replaceLiteral("A.a.", "A", "x", true, global = false) shouldBe Right("x.a.")
  }
}
