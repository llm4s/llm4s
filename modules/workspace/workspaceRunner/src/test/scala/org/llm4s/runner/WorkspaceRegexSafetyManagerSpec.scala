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
      "(?:a?)" * 30 + "(?!)"
    ).foreach(pattern => withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isRight shouldBe true))
  }

  // Codex on #3135: a `b` after the chain hid the blow-up, because on an empty input the JDK's minimum-length check
  // fails the match before trying any path. A failing zero-width suffix is tried on every path: `(?:a?)?` repeated 25
  // times then `(?!$)` took 1.7 s without a read.
  it should "refuse a long chain of optional empty-matching parts before a failing zero-width suffix" in {
    for {
      unit   <- Seq("(?:a?)?", "(\\s*)?", "(a|)?", "(?:a?)*", "(?:a?){0,2}", "(?:|)")
      suffix <- Seq("(?!)", "(?!$)")
    } {
      val pattern = unit * 40 + suffix
      withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isLeft shouldBe true)
    }
  }

  it should "accept only chains that match quickly, with nothing read, before a failing zero-width suffix" in {
    // for each part, the longest chain the check accepts (up to 60) must finish at once on an empty and a short input:
    // a model that undercounts a part's empty matches accepts a chain that takes seconds, and the guard fails it
    val units = Seq(
      "(?:a?)?",
      "(\\s*)?",
      "(a|)?",
      "(?:a?)*",
      "(?:a?){0,2}",
      "(?:|)",
      "(?:a|)",
      "(?:a?)",
      "(?:a?){3}",
      "(?:a?)+",
      "(?=a?)",
      "(?:\\b)*",
      "(a?)\\1",
      // the audit of #3135: constructs that may move or retry without reading
      "(a?)(?:\\1)?",
      "(?:\\G)?",
      "(?:\\G|)",
      "(?:\\R)?",
      "(?:\\X)?",
      "\\p{L}?",
      "(?i:a?)?",
      "(?iu:\u00e9?)?",
      "(?:(?=a?)a?)?",
      "(?:(?=a?))*",
      "(?>a?)?",
      "(?>(?:a?)*)",
      "(?:a?)?+",
      "(?<=a{0,64})",
      "(?<!\\d{1,3})"
    )
    for {
      unit   <- units
      suffix <- Seq("(?!)", "(?!$)", "b")
    } {
      val accepted = (1 to 60).takeWhile(n => WorkspaceRegexSafetyManager.safeCompile(unit * n + suffix).isRight)
      accepted.lastOption.foreach { n =>
        val pattern = Pattern.compile(unit * n + suffix)
        withClue(s"$unit x$n $suffix") {
          withinGuard {
            WorkspaceRegexSafetyManager.safeFind(pattern, "")
            WorkspaceRegexSafetyManager.safeFind(pattern, "xyz", maxSteps = 100000L)
          }
        }
      }
    }
  }

  // Codex on #3135: the JDK tries a lookbehind's body from each earlier position its length allows, moving back
  // without reading, and nested lookarounds multiply the scans: this ran past 12 s on a 1000-character line.
  it should "refuse a long or unbounded lookbehind, and one holding another lookaround" in {
    val nested = (1 to 4).foldLeft("")((p, _) => "(?<!" + p + "(?!)a{0,1000})")
    Seq(
      "$" + nested,
      "(?<!(?!)a{0,1000})",
      "(?<=a{0,65})x",
      "(?<=(?=a)a)x",
      "(?<=a\\X)x",
      "(a)(?<=\\1)x"
    ).foreach(pattern => withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isLeft shouldBe true))
    Seq("(?<=\\d{1,3})x", "(?<!\\\\)\"", "(?<=^|,)\\w+", "(?<=a{0,64})x", "(?<![a-z]{2})\\d")
      .foreach(pattern => withClue(pattern)(WorkspaceRegexSafetyManager.safeCompile(pattern).isRight shouldBe true))
  }

  it should "bound the longest accepted chain of lookbehinds on a long line" in {
    val unit     = "(?<=a{0,64})"
    val accepted = (1 to 60).takeWhile(n => WorkspaceRegexSafetyManager.safeCompile(unit * n + "(?!)").isRight)
    accepted should not be empty
    val pattern = Pattern.compile(unit * accepted.last + "(?!)")
    withinGuard(WorkspaceRegexSafetyManager.safeFind(pattern, "a" * 1000))
    withinGuard(WorkspaceRegexSafetyManager.safeFind(pattern, "x" * 1000))
  }

  // Codex on #3135: `(?i){2147483647}` kept the check's own loop running 2^31 times, since its body costs nothing and
  // the cost never grew. The check's work must be bounded by the pattern's length, never by a number written in it.
  private def countsAndNesting = Seq(
    "(?i){2147483647}",
    "(?:){2147483647}",
    "(?=){2147483647}",
    "(?i){0,2147483647}",
    "(?:(?i){2147483647}){2147483647}",
    "(?:(?:){2147483647}){2147483647}x",
    "(" * 499 + "a" + ")" * 499,
    "(?:" * 240 + "a" + ")" * 240,
    "(?=" * 240 + ")" * 240,
    "(?<=" * 190 + "a" + ")" * 190,
    "[" * 499 + "a" + "]" * 499,
    "(?:a|" * 160 + ")" * 160,
    "a?" * 500
  )

  it should "check a pattern in time bounded by its length, whatever counts or nesting it writes" in {
    countsAndNesting.foreach(pattern =>
      withClue(pattern.take(40))(withinGuard(WorkspaceRegexSafetyManager.safeCompile(pattern)))
    )
  }

  it should "check a deeply nested pattern without overflowing a small stack" in {
    countsAndNesting.foreach { pattern =>
      withClue(pattern.take(40)) {
        @volatile var escaped: Option[Throwable] = None
        val thread =
          new Thread(null, () => RegexShape.refusal(pattern, 0, 1000): Unit, "small-stack-shape", 256L * 1024)
        thread.setUncaughtExceptionHandler((_, e) => escaped = Some(e))
        thread.start()
        thread.join(5000)
        thread.isAlive shouldBe false
        escaped shouldBe None
      }
    }
  }

  // Codex on #3135: an inline flag cost nothing, so this passed the check, yet the JDK runs every required iteration
  // whatever the body does: 5.2 s on an empty input with no read. Every iteration is now at least one step.
  it should "refuse many required iterations of a part that does nothing" in {
    Seq("(?i){2147483647}" * 50 + "(?!)", "(?i){1001}(?!)", "(?:){1001}(?!)", "(?:^){1001}(?!)")
      .foreach(pattern =>
        withClue(pattern.take(40))(WorkspaceRegexSafetyManager.safeCompile(pattern).isLeft shouldBe true)
      )
  }

  it should "match every accepted repetition of a zero-width part quickly before a failing suffix" in {
    val units    = Seq("(?i)", "(?:)", "()", "^", "$", "\\b", "\\B", "\\G", "(?=)", "(?!x)", "(?<=)", "(?<!x)")
    val counts   = Seq("1", "10", "100", "499", "999", "1000", "0,999", "0,2147483647", "2147483647")
    var accepted = 0
    for {
      unit  <- units
      count <- counts
      n     <- Seq(1, 2, 10, 50)
    } {
      val source = s"(?:$unit){$count}" * n + "(?!)"
      WorkspaceRegexSafetyManager.safeCompile(source).foreach { pattern =>
        accepted += 1
        withClue(source.take(60)) {
          withinGuard {
            WorkspaceRegexSafetyManager.safeFind(pattern, "")
            WorkspaceRegexSafetyManager.safeFind(pattern, "xyz", maxSteps = 100000L)
          }
        }
      }
    }
    accepted should be > 0
  }

  // Runs `body` on a daemon thread and fails if it has not finished within the guard, rather than hanging the suite.
  private def withinGuard(body: => Any): Unit = {
    @volatile var done = false
    val thread         = new Thread(() => { body; done = true }, "regex-guard")
    thread.setDaemon(true)
    thread.start()
    thread.join(5000)
    if (!done) fail("did not finish within the 5 s guard")
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
