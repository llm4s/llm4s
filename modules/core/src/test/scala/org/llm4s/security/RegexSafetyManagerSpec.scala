package org.llm4s.security

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.regex.Pattern

class RegexSafetyManagerSpec extends AnyFlatSpec with Matchers {

  private val recursivePattern = Pattern.compile("(a|aa)*b")
  private val recursiveInput   = "a" * 50000

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
    result.getOrElse(fail("the match produced no result"))
  }

  "RegexSafetyManager.safeCompile" should "accept a quantified alternation whose alternatives do not overlap (#2825)" in {
    Seq("(foo|bar)+", "(?:jpg|png)*", "^(GET|POST)+$", "(?<verb>GET|POST)+ /").foreach { pattern =>
      withClue(pattern)(RegexSafetyManager.safeCompile(pattern).isRight shouldBe true)
    }
  }

  it should "still reject a quantified alternation whose alternatives overlap, and nested quantifiers" in {
    Seq("(a|a)*b", "(a|aa)+", "(ab|a)*c", "(?:a|ab)*c", "(?<x>a|ab)+", "(a+)+b", "((a+)+)+b").foreach { pattern =>
      withClue(pattern)(RegexSafetyManager.safeCompile(pattern).isLeft shouldBe true)
    }
  }

  "RegexSafetyManager.safeFind" should "match normally within the budget" in {
    RegexSafetyManager.safeFind(Pattern.compile("b+"), "aabbb") shouldBe Right(true)
    RegexSafetyManager.safeFind(Pattern.compile("^a+$"), "aaab") shouldBe Right(false)
  }

  it should "return a Left when the match exceeds its step budget" in {
    val result = RegexSafetyManager.safeFind(Pattern.compile("(a+)+c"), "a" * 30 + "b", maxSteps = 1000L)
    result shouldBe Left(
      ValidationError("pattern", "Regex matching aborted: exceeded complexity budget (possible ReDoS)")
    )

    RegexSafetyManager.safeFind(Pattern.compile("^a+$"), "aaaa", maxSteps = 1L).isLeft shouldBe true
  }

  it should "return a Left when a recursive pattern overflows the stack" in {
    val expected = Left(
      ValidationError("pattern", "Regex matching aborted: pattern recursed too deeply for the input (stack overflow)")
    )
    onSmallStack(RegexSafetyManager.safeFind(recursivePattern, recursiveInput)) shouldBe expected
  }

  it should "keep matching on the same thread after a stack overflow" in {
    onSmallStack {
      RegexSafetyManager.safeFind(recursivePattern, recursiveInput)
      RegexSafetyManager.safeFind(recursivePattern, "aaab")
    } shouldBe Right(true)
  }

  it should "return a Left when the match fails with any other non-fatal exception" in {
    // A null pattern makes building the matcher throw NullPointerException inside the guard.
    val result = RegexSafetyManager.safeFind(null, "abc")
    result.isLeft shouldBe true
    result.left.toOption.get.message should startWith("Invalid pattern: Regex matching failed:")
  }

  it should "reject null and oversized input before matching" in {
    RegexSafetyManager.safeFind(Pattern.compile("a"), null) shouldBe Left(
      ValidationError("input", "Input cannot be null")
    )
    RegexSafetyManager.safeFind(Pattern.compile("a"), "a" * 100001) shouldBe Left(
      ValidationError("input", "Input too large: 100001 > 100000")
    )
  }

  "RegexSafetyManager.safeCompile" should "refuse a null, blank, oversized or malformed pattern with a ValidationError" in {
    RegexSafetyManager.safeCompile(null) shouldBe Left(ValidationError("pattern", "Pattern cannot be null"))
    RegexSafetyManager.safeCompile("  ") shouldBe Left(ValidationError("pattern", "Pattern cannot be empty"))
    RegexSafetyManager.safeCompile("a" * 1001) shouldBe Left(
      ValidationError("pattern", "Pattern too long: 1001 > 1000")
    )
    val malformed = RegexSafetyManager.safeCompile("(").left.toOption.get
    malformed shouldBe a[ValidationError]
    malformed.message should startWith("Invalid pattern: Invalid regex syntax:")
    RegexSafetyManager.safeCompile("(a+)+b").left.toOption.get.message shouldBe
      "Invalid pattern: Regex pattern contains nested quantifiers and may cause ReDoS"
  }

  it should "compile with the flags it is given" in {
    val compiled = RegexSafetyManager.safeCompile("(get|post)+", Pattern.CASE_INSENSITIVE).toOption.get
    RegexSafetyManager.safeFind(compiled, "GETPOST /") shouldBe Right(true)
  }

  // #3051: the budget counts reads, and these shapes work without reading. `(){2000000000}b` took 18 s on a
  // 40-character line with 40 reads counted; `(?:|)` repeated tries 2^n empty paths on an empty input.
  it should "refuse a pattern whose matching the step budget cannot bound" in {
    Seq(
      "(){2000000000}b",
      "(?:a?){3}b",
      "(?=a)*b",
      "\\b+",
      "(a?)\\1+",
      "(a?)\\Q\\E*",
      "(?:|)" * 40 + "b",
      "(a?|b?)c",
      "(?x)a"
    ).foreach { pattern =>
      withClue(pattern) {
        val refused = RegexSafetyManager.safeCompile(pattern).left.toOption
        refused shouldBe a[Some[?]]
        refused.get.message should startWith("Invalid pattern:")
      }
    }
    RegexSafetyManager.safeCompile("a", Pattern.COMMENTS).isLeft shouldBe true
  }

  it should "accept ordinary patterns, a long counted repetition among them" in {
    Seq("^[a-z0-9_-]+$", "^.{0,4096}$", "(\\w)\\1+", "(foo|)bar", "^\\+?\\d{1,3}(?:[ -]?\\d{2,4})?$", "[^/]*\\.md")
      .foreach(pattern => withClue(pattern)(RegexSafetyManager.safeCompile(pattern).isRight shouldBe true))
  }
}
