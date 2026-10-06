package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.regex.Pattern

class WorkspaceRegexSafetyManagerSpec extends AnyFlatSpec with Matchers {

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
}
