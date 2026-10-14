package org.llm4s.agent.harness

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VPathSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def p(s: String): VPath = VPath(s).value

  "VPath" should "accept an absolute path of non-empty segments" in {
    p("/src/a.scala").value shouldBe "/src/a.scala"
    p("/src/a.scala").segments shouldBe Vector("src", "a.scala")
    p("/").segments shouldBe Vector.empty
    VPath.root.value shouldBe "/"
  }

  it should "drop one trailing slash" in {
    p("/src/").value shouldBe "/src"
  }

  it should "refuse a relative path, an empty segment, '.', '..', NUL, a backslash and control characters" in {
    Seq(
      "",
      "src/a",
      "/a\ud800b",
      "/\udc00",
      "//a",
      "/a//b",
      "/a/./b",
      "/a/../b",
      "/..",
      "/a\u0000b",
      "/a\\b",
      "/a\nb",
      "/a//"
    )
      .foreach(s => withClue(s"'$s': ")(VPath(s).isLeft shouldBe true))
  }

  it should "refuse a path over 1024 bytes or a segment over 255" in {
    VPath("/" + "a" * 255).isRight shouldBe true
    VPath("/" + "a" * 256).isLeft shouldBe true
    VPath(("/" + "a" * 200) * 6).isLeft shouldBe true
  }

  it should "build children, parents and names" in {
    (p("/src") / "a.scala").value shouldBe p("/src/a.scala")
    (VPath.root / "a").value shouldBe p("/a")
    (p("/src") / "a/b").isLeft shouldBe true
    (p("/src") / "..").isLeft shouldBe true
    p("/src/a.scala").parent shouldBe Some(p("/src"))
    p("/src").parent shouldBe Some(VPath.root)
    VPath.root.parent shouldBe None
    p("/src/a.scala").name shouldBe "a.scala"
    VPath.root.name shouldBe ""
  }

  it should "test containment by segment" in {
    p("/a/b/c").isWithin(p("/a/b")) shouldBe true
    p("/a/b").isWithin(p("/a/b")) shouldBe true
    p("/a/bc").isWithin(p("/a/b")) shouldBe false
    p("/a").isWithin(VPath.root) shouldBe true
    p("/a/b/c").relativeTo(p("/a")) shouldBe Some("b/c")
    p("/a").relativeTo(p("/a")) shouldBe Some("")
    p("/ab").relativeTo(p("/a")) shouldBe None
    p("/a/b").relativeTo(VPath.root) shouldBe Some("a/b")
  }

  it should "rebase a path from one ancestor onto another" in {
    p("/memories/notes.md").rebase(p("/memories"), VPath.root) shouldBe Some(p("/notes.md"))
    p("/notes.md").rebase(VPath.root, p("/memories")) shouldBe Some(p("/memories/notes.md"))
    p("/memories").rebase(p("/memories"), VPath.root) shouldBe Some(VPath.root)
    p("/other").rebase(p("/memories"), VPath.root) shouldBe None
  }

  it should "not rebase onto a path too long to be one" in {
    val longMount = p("/" + "m" * 255 + "/" + "n" * 255 + "/" + "o" * 255)
    val longFile  = p("/" + "a" * 255 + "/" + "b" * 255)
    longFile.rebase(VPath.root, longMount) shouldBe None
    p("/x").rebase(VPath.root, longMount).map(_.value.length) shouldBe Some(longMount.value.length + 2)
  }

  it should "round-trip as a JSON string, and refuse an invalid one on read" in {
    upickle.default.write(p("/a/b")) shouldBe "\"/a/b\""
    upickle.default.read[VPath]("\"/a/b\"") shouldBe p("/a/b")
    an[Exception] should be thrownBy upickle.default.read[VPath]("\"/a/../b\"")
  }

  it should "order by value" in {
    Vector(p("/b"), p("/a/c"), p("/a")).sorted shouldBe Vector(p("/a"), p("/a/c"), p("/b"))
  }

  "VPath" should "refuse an over-long input by its length first, quoting only a bounded prefix of it" in {
    val huge    = "/" + "a" * 10000000
    val refusal = VPath(huge).left.value
    refusal.message should include("longer than 1024 bytes")
    refusal.message.length should be < 300
    // a multi-byte path within 1025 characters but over 1024 bytes is refused by its counted size
    VPath("/" + "\u20ac" * 400).left.value.message should include("longer than 1024 bytes")
    val name = (VPath.root / ("b" * 10000000)).left.value
    name.message should include("longer than 255 bytes")
    name.message.length should be < 300
    (VPath.root / ("x/" * 100)).left.value.message.length should be < 300
  }

  "Text.quoted" should "keep a short input whole and cut a long one on a code point boundary, with its length" in {
    org.llm4s.agent.harness.Text.quoted("short") shouldBe "short"
    val cut = org.llm4s.agent.harness.Text.quoted("a" * 63 + "\uD83D\uDE00" + "b" * 100)
    cut shouldBe ("a" * 63 + "... (165 characters)")
  }

}
