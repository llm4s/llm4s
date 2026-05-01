package org.llm4s.samples.chat.tui

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import termflow.tui.{ Style, Text, Theme }

class ChatTuiTranscriptSpec extends AnyFlatSpec with Matchers {

  private val theme = Theme.dark

  private def textsOf(lines: Vector[Vector[Text]]): Vector[String] =
    lines.map(_.map(_.txt).mkString)

  "renderEntries" should "split content on embedded newlines" in {
    val entry = Entry(Role.Assistant, "first line\nsecond line\nthird line")
    val lines = ChatTuiTranscript.renderEntries(Vector(entry), theme)
    val texts = textsOf(lines)
    texts should have size 3
    texts.head should startWith("assistant: first line")
    texts(1) should include("second line")
    texts(2) should include("third line")
  }

  it should "color the role prefix distinctly per role" in {
    val sysLine  = ChatTuiTranscript.renderEntries(Vector(Entry(Role.System, "hello")), theme).head
    val youLine  = ChatTuiTranscript.renderEntries(Vector(Entry(Role.User, "hi")), theme).head
    val asstLine = ChatTuiTranscript.renderEntries(Vector(Entry(Role.Assistant, "hey")), theme).head

    val sysPrefix  = sysLine.head
    val youPrefix  = youLine.head
    val asstPrefix = asstLine.head

    sysPrefix.txt should startWith("system:")
    youPrefix.txt should startWith("you:")
    asstPrefix.txt should startWith("assistant:")
    Set(sysPrefix.style.fg, youPrefix.style.fg, asstPrefix.style.fg).size shouldBe 3
  }

  "parseInline" should "render **bold** as a bold styled segment" in {
    val entry = Entry(Role.Assistant, "this is **important** text")
    val line  = ChatTuiTranscript.renderEntries(Vector(entry), theme).head
    val texts = line.map(_.txt).mkString
    texts shouldNot include("**")
    val boldSegments = line.filter(t => t.style.bold && t.txt.contains("important"))
    boldSegments should not be empty
  }

  it should "leave unterminated **runs as plain text" in {
    val entry = Entry(Role.Assistant, "rogue ** asterisk")
    val line  = ChatTuiTranscript.renderEntries(Vector(entry), theme).head
    val texts = line.map(_.txt).mkString
    texts should include("**")
  }

  "wrap" should "break long lines at word boundaries" in {
    val seg   = Vector(Text("hello world this is a wrap test", Style()))
    val out   = ChatTuiTranscript.wrap(seg, width = 10)
    val texts = out.map(_.map(_.txt).mkString)
    // Each line should be <= 10 chars and never split a word mid-character
    // unless the word itself is longer than the width.
    texts.foreach(line => line.length should be <= 10)
    texts.mkString(" ").replaceAll("\\s+", " ").trim shouldBe "hello world this is a wrap test"
  }

  it should "fall back to char break for words longer than the width" in {
    val seg   = Vector(Text("supercalifragilisticexpialidocious", Style()))
    val out   = ChatTuiTranscript.wrap(seg, width = 8)
    val texts = out.map(_.map(_.txt).mkString)
    texts.foreach(line => line.length should be <= 8)
    texts.mkString shouldBe "supercalifragilisticexpialidocious"
  }

  "maxScroll" should "be zero when content fits inside the viewport" in {
    val rich = ChatTuiTranscript.renderEntries(Vector(Entry(Role.User, "short")), theme)
    ChatTuiTranscript.maxScroll(rich, width = 80, height = 10) shouldBe 0
  }

  it should "report a positive offset when content exceeds the viewport" in {
    val long = (1 to 50).map(i => s"line $i").mkString("\n")
    val rich = ChatTuiTranscript.renderEntries(Vector(Entry(Role.Assistant, long)), theme)
    ChatTuiTranscript.maxScroll(rich, width = 80, height = 5) should be > 0
  }
}
