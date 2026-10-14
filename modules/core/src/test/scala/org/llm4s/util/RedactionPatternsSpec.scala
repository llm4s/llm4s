package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.matching.Regex

/**
 * `Redaction` reads its input in one forward pass with no regular expression (#1932), so its cost cannot depend on
 * how a backtracking matcher meets a pattern. These static checks keep it so: no field of the redaction's classes holds
 * a pattern, and the patterns that remain in `SecretPatterns` - the `SecretLeakGuardrail`'s, which the redaction
 * matches by hand - hold no nested quantifier, the shape that makes a backtracking match grow faster than linearly.
 */
class RedactionPatternsSpec extends AnyFlatSpec with Matchers {

  /**
   * Whether `pattern` repeats a group that holds a quantifier itself - `(a+)+`, `(?:\w*)*`, `(x{2,})?` - read past
   * escapes and character classes. A group's own `?` (`(?:`, `(?<!`) is no quantifier.
   */
  private[util] def hasNestedQuantifier(pattern: String): Boolean = {
    var i       = 0
    var inClass = false
    // for each open group, whether it holds a quantifier
    val groups = scala.collection.mutable.Stack.empty[Boolean]
    var found  = false
    def quantifierAt(k: Int): Boolean =
      k < pattern.length && ("*+?".indexOf(pattern.charAt(k).toInt) >= 0 || pattern.charAt(k) == '{')
    def markQuantified(): Unit = if (groups.nonEmpty) groups(0) = true
    while (i < pattern.length && !found) {
      val c = pattern.charAt(i)
      if (c == '\\') {
        i += 2
        if (!inClass && quantifierAt(i)) markQuantified()
      } else if (inClass) {
        i += 1
        if (c == ']') { inClass = false; if (quantifierAt(i)) markQuantified() }
      } else if (c == '[') { inClass = true; i += 1 }
      else if (c == '(') {
        groups.push(false)
        i += 1
        if (i < pattern.length && pattern.charAt(i) == '?') {
          // the group's own syntax: `?:`, `?=`, `?!`, `?<=`, `?<!`, `?<name>`, `?i:`
          i += 1
          while (i < pattern.length && ":=!<>".indexOf(pattern.charAt(i).toInt) < 0 && pattern.charAt(i) != ')') i += 1
          if (
            i < pattern.length && pattern.charAt(i) == '<' && i + 1 < pattern.length && "=!".indexOf(
              pattern.charAt(i + 1).toInt
            ) >= 0
          ) i += 2
          else if (i < pattern.length && pattern.charAt(i) != ')') i += 1
        }
      } else if (c == ')') {
        val inner = if (groups.nonEmpty) groups.pop() else false
        i += 1
        if (quantifierAt(i)) { if (inner) found = true; markQuantified() }
        else if (inner) markQuantified()
      } else {
        i += 1
        if (quantifierAt(i)) markQuantified()
      }
    }
    found
  }

  "the nested-quantifier check" should "tell the shapes apart" in {
    Seq("(a+)+", "(?:\\w*)*", "(x{2,})?", "((ab)*c)+", "(?:a|b+)*").foreach(p => hasNestedQuantifier(p) shouldBe true)
    Seq("a+b*", "(?:proj-)?[A-Za-z0-9]{20,}", "[(a+)]+", "\\(a+\\)+", "(?<!x)a+", "(?:ab)+").foreach { p =>
      withClue(p)(hasNestedQuantifier(p) shouldBe false)
    }
  }

  "Redaction" should "hold no regular expression in any field of its classes (#1932)" in {
    val classes = Seq(
      Redaction.getClass,
      RedactionScanner.getClass,
      classOf[RedactionScanner.Scan],
      classOf[RedactionScanner.Text],
      classOf[RedactionScanner.Edits],
      classOf[RedactionScanner.Walk]
    )
    for {
      cls   <- classes
      field <- cls.getDeclaredFields
    } withClue(s"${cls.getName}.${field.getName}: ") {
      classOf[Regex].isAssignableFrom(field.getType) shouldBe false
      classOf[java.util.regex.Pattern].isAssignableFrom(field.getType) shouldBe false
    }
  }

  "SecretPatterns" should "hold no nested quantifier in any pattern (#1932)" in {
    SecretPatterns.SecretType.all.foreach { t =>
      withClue(s"${t.name}: ${t.pattern.regex}: ")(hasNestedQuantifier(t.pattern.regex) shouldBe false)
    }
  }
}
