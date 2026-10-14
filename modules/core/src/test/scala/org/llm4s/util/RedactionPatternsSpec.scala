package org.llm4s.util

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.matching.Regex

/**
 * `Redaction` reads its input in one forward pass with no regular expression (#1932), so its cost cannot depend on
 * how a backtracking matcher meets a pattern. These static checks keep it so: no field of the redaction's classes holds
 * a pattern, and the patterns that remain in `SecretPatterns` - the `SecretLeakGuardrail`'s, which the redaction
 * matches by hand - hold no nested quantifier, the shape that makes a backtracking match grow faster than linearly.
 */
class RedactionPatternsSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 3000)

  /** Text of the pieces the vendor key shapes are made of, joined at random: prefixes, runs, dots, dashes. */
  private val genKeyText: Gen[String] =
    Gen
      .listOf(
        Gen.oneOf(
          Gen.oneOf("sk-", "sk-proj-", "sk-ant-", "sk-lf-", "pk-lf-", "pa-", "AIza", "AKIA", "eyJ", ".", "-", "_", " "),
          Gen.choose(1, 40).flatMap(k => Gen.listOfN(k, Gen.alphaNumChar).map(_.mkString)),
          Gen.choose(1, 20).flatMap(k => Gen.listOfN(k, Gen.oneOf('A', 'Z', '0', '9')).map(_.mkString))
        )
      )
      .map(_.mkString)

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

  it should "be matched by the redaction wherever a default pattern matches (#1932)" in {
    // The redaction matches the default shapes by hand; a match of a pattern is never left readable.
    forAll(genKeyText) { text =>
      val out = Redaction.redact(text)
      SecretPatterns.detect(text).foreach { m =>
        withClue(s"${m.secretType.name} in $text, redacted $out: ")((out should not).include(m.value))
      }
    }
  }

  "RedactionScanner.Edits" should "join overlapping replacements and sort those added out of order" in {
    val e = new RedactionScanner.Edits("[R]")
    e.add(2, 4, "[R]")
    e.add(3, 6, "\"[R]\"") // overlaps the last: joined into one placeholder
    e.add(8, 9, "[R]")
    e.applyTo("0123456789") shouldBe "01[R]67[R]9"
    val o = new RedactionScanner.Edits("[R]")
    o.add(6, 8, "[R]")
    o.add(1, 3, "[R]") // out of order: sorted when applied
    o.add(2, 7, "[R]") // overlaps both
    o.add(9, 9, "[R]") // empty: ignored
    o.applyTo("0123456789") shouldBe "0[R]89"
  }
}
