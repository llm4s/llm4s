package org.llm4s.security

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

  "RegexSafetyManager.safeFind" should "match normally within the budget" in {
    RegexSafetyManager.safeFind(Pattern.compile("b+"), "aabbb") shouldBe Right(true)
    RegexSafetyManager.safeMatches(Pattern.compile("a+"), "aaab") shouldBe Right(false)
  }

  it should "return a Left when the match exceeds its step budget" in {
    val result = RegexSafetyManager.safeFind(Pattern.compile("(a+)+c"), "a" * 30 + "b", maxSteps = 1000L)
    result shouldBe Left("Regex matching aborted: exceeded complexity budget (possible ReDoS)")

    RegexSafetyManager.safeMatches(Pattern.compile("a+"), "aaaa", maxSteps = 1L).isLeft shouldBe true
  }

  it should "return a Left when a recursive pattern overflows the stack" in {
    val expected = Left("Regex matching aborted: pattern recursed too deeply for the input (stack overflow)")
    onSmallStack(RegexSafetyManager.safeFind(recursivePattern, recursiveInput)) shouldBe expected
    onSmallStack(RegexSafetyManager.safeMatches(recursivePattern, recursiveInput)) shouldBe expected
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
    result.left.toOption.get should startWith("Regex matching failed:")
  }

  it should "reject null and oversized input before matching" in {
    RegexSafetyManager.safeFind(Pattern.compile("a"), null) shouldBe Left("Input cannot be null")
    RegexSafetyManager.safeFind(Pattern.compile("a"), "a" * 100001).isLeft shouldBe true
  }
}
