package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.AssistantError

/**
 * Tests for Command parsing
 */
class CommandSpec extends AnyFlatSpec with Matchers {

  // ============ Command.Help ============

  "Command.parse" should "parse /help command" in {
    Command.parse("/help") shouldBe Right(Command.Help)
  }

  it should "parse /help with extra text" in {
    Command.parse("/help please") shouldBe Right(Command.Help)
  }

  it should "be case insensitive for /help" in {
    Command.parse("/HELP") shouldBe Right(Command.Help)
    Command.parse("/Help") shouldBe Right(Command.Help)
  }

  // ============ Command.New ============

  it should "parse /new command" in {
    Command.parse("/new") shouldBe Right(Command.New)
  }

  it should "parse /new with extra text" in {
    Command.parse("/new session") shouldBe Right(Command.New)
  }

  it should "be case insensitive for /new" in {
    Command.parse("/NEW") shouldBe Right(Command.New)
    Command.parse("/New") shouldBe Right(Command.New)
  }

  // ============ Command.Save ============

  it should "parse /save with title" in {
    // Note: implementation lowercases entire input including title
    Command.parse("/save My Session") shouldBe Right(Command.Save("my session"))
  }

  it should "parse /save with multi-word title" in {
    // Note: implementation lowercases entire input including title
    Command.parse("/save My Very Long Session Title") shouldBe Right(Command.Save("my very long session title"))
  }

  it should "parse /save without title using default" in {
    Command.parse("/save") shouldBe Right(Command.Save("Saved Session"))
  }

  it should "parse /save with only whitespace using default" in {
    Command.parse("/save   ") shouldBe Right(Command.Save("Saved Session"))
  }

  it should "be case insensitive for /save" in {
    Command.parse("/SAVE test") shouldBe Right(Command.Save("test"))
    Command.parse("/Save test") shouldBe Right(Command.Save("test"))
  }

  // ============ Command.Load ============

  it should "parse /load with title" in {
    // Note: implementation lowercases entire input including title
    Command.parse("/load My Session") shouldBe Right(Command.Load("my session"))
  }

  it should "parse /load with quoted title" in {
    // Note: implementation lowercases entire input including title
    Command.parse("/load \"My Session\"") shouldBe Right(Command.Load("my session"))
  }

  it should "parse /load with multi-word title" in {
    // Note: implementation lowercases entire input including title
    Command.parse("/load Previous Chat About Code") shouldBe Right(Command.Load("previous chat about code"))
  }

  it should "return error for /load without title" in {
    val result = Command.parse("/load")
    result.isLeft shouldBe true
    result.left.getOrElse(fail()) shouldBe a[AssistantError]
  }

  it should "return error for /load with only whitespace" in {
    val result = Command.parse("/load   ")
    result.isLeft shouldBe true
  }

  it should "be case insensitive for /load" in {
    Command.parse("/LOAD test") shouldBe Right(Command.Load("test"))
    Command.parse("/Load test") shouldBe Right(Command.Load("test"))
  }

  // ============ Command.Sessions ============

  it should "parse /sessions command" in {
    Command.parse("/sessions") shouldBe Right(Command.Sessions)
  }

  it should "parse /sessions with extra text" in {
    Command.parse("/sessions list") shouldBe Right(Command.Sessions)
  }

  it should "be case insensitive for /sessions" in {
    Command.parse("/SESSIONS") shouldBe Right(Command.Sessions)
    Command.parse("/Sessions") shouldBe Right(Command.Sessions)
  }

  // ============ Command.Quit ============

  it should "parse /quit command" in {
    Command.parse("/quit") shouldBe Right(Command.Quit)
  }

  it should "parse /quit with extra text" in {
    Command.parse("/quit now") shouldBe Right(Command.Quit)
  }

  it should "be case insensitive for /quit" in {
    Command.parse("/QUIT") shouldBe Right(Command.Quit)
    Command.parse("/Quit") shouldBe Right(Command.Quit)
  }

  // ============ Unknown Commands ============

  it should "return error for unknown commands" in {
    val result = Command.parse("/unknown")
    result.isLeft shouldBe true
    result.left.getOrElse(fail()) shouldBe a[AssistantError]
  }

  it should "return error for non-command input" in {
    val result = Command.parse("just some text")
    result.isLeft shouldBe true
  }

  it should "return error for empty command" in {
    val result = Command.parse("/")
    result.isLeft shouldBe true
  }

  it should "return error for invalid slash commands" in {
    val result = Command.parse("/notacommand")
    result.isLeft shouldBe true
  }
}
