package org.llm4s.agent.graph.tool

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GlobSpec extends AnyFlatSpec with Matchers {

  "A literal glob" should "match only its own text" in {
    Glob("search").matches("search") shouldBe true
    Glob("search").matches("searchx") shouldBe false
    Glob("a.b").matches("axb") shouldBe false // '.' is literal, not a regex wildcard
    Glob("(x)+").matches("(x)+") shouldBe true
  }

  "*" should "match within one segment only" in {
    Glob("/src/*.scala").matches("/src/A.scala") shouldBe true
    Glob("/src/*.scala").matches("/src/a/A.scala") shouldBe false
    Glob("*").matches("") shouldBe true
    Glob("*").matches("a/b") shouldBe false
  }

  "?" should "match one character other than '/'" in {
    Glob("a?c").matches("abc") shouldBe true
    Glob("a?c").matches("ac") shouldBe false
    Glob("a?c").matches("a/c") shouldBe false
  }

  "**" should "match across segments, and zero segments as a whole segment" in {
    Glob("**").matches("a/b/c") shouldBe true
    Glob("/src/**").matches("/src") shouldBe true
    Glob("/src/**").matches("/src/a/b.scala") shouldBe true
    Glob("/src/**").matches("/srcx") shouldBe false
    Glob("/src/**/a.scala").matches("/src/a.scala") shouldBe true
    Glob("/src/**/a.scala").matches("/src/x/y/a.scala") shouldBe true
    Glob("/src/**/a.scala").matches("/src/xa.scala") shouldBe false
    Glob("**/*.scala").matches("A.scala") shouldBe true
    Glob("**/*.scala").matches("x/y/A.scala") shouldBe true
    Glob("https://api.example.com/**").matches("https://api.example.com/v1/x") shouldBe true
    Glob("https://api.example.com/**").matches("https://api.example.com.evil/v1") shouldBe false
  }

  it should "inside a segment match any run of characters" in {
    Glob("/a**z").matches("/a/b/z") shouldBe true
  }

  "Two globs" should "be equal by pattern" in {
    Glob("/src/**") shouldBe Glob("/src/**")
    Glob("/src/**").hashCode shouldBe Glob("/src/**").hashCode
    Glob("/src/**").pattern shouldBe "/src/**"
  }

  "overlaps" should "hold when some target matches both patterns" in {
    Glob("/out/**").overlaps(Glob("/out/secrets/**")) shouldBe true
    Glob("/out/secrets/**").overlaps(Glob("/out/**")) shouldBe true
    Glob("/out/*.txt").overlaps(Glob("/out/a.*")) shouldBe true // /out/a.txt
    Glob("/out/*").overlaps(Glob("/out/a/b")) shouldBe false
    Glob("/src/**").overlaps(Glob("/tmp/**")) shouldBe false
    Glob("a?c").overlaps(Glob("a/c")) shouldBe false
    Glob("x").overlaps(Glob("x")) shouldBe true
  }

  "covers" should "hold when every target of the other matches this pattern" in {
    Glob("/out/**").covers(Glob("/out/secrets/**")) shouldBe true
    Glob("/out/secrets/**").covers(Glob("/out/**")) shouldBe false
    Glob("/out/**").covers(Glob("/out")) shouldBe true
    Glob("/out/*").covers(Glob("/out/**")) shouldBe false
    Glob("**").covers(Glob("/anything/**")) shouldBe true
    Glob("/src/**/*.scala").covers(Glob("/src/a/*.scala")) shouldBe true
    Glob("/src/**/*.scala").covers(Glob("/src/a/*")) shouldBe false
    Glob("/a/b").covers(Glob("/a/b")) shouldBe true
    Glob("/a/b").covers(Glob("/a/c")) shouldBe false
  }

  "A concrete target" should "be overlapped and covered exactly when it matches" in {
    for {
      pattern <- Seq("/out/**", "/out/*.txt", "/src/**/a.scala", "x?z")
      target  <- Seq("/out/a.txt", "/src/a.scala", "xyz")
    }
      withClue(s"$pattern vs $target: ") {
        Glob(pattern).overlaps(Glob(target)) shouldBe Glob(pattern).matches(target)
        Glob(pattern).covers(Glob(target)) shouldBe Glob(pattern).matches(target)
      }
  }
}
