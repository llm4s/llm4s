package org.llm4s.llmconnect.provider

import org.llm4s.error.ProcessingError
import org.llm4s.testutil.SmallStack
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ToolCallArgumentsTest extends AnyFunSuite with Matchers {

  private def malformed(raw: String): Boolean =
    ToolCallRules.parseArguments(raw) match {
      case Left(e: ProcessingError) => e.message.contains("malformed tool call")
      case _                        => false
    }

  test("empty or blank input is {}, a call with no arguments") {
    ToolCallRules.parseArguments("") shouldBe Right(ujson.Obj())
    ToolCallRules.parseArguments("  ") shouldBe Right(ujson.Obj())
  }

  test("a JSON object is parsed as-is") {
    ToolCallRules.parseArguments("""{"location":"SF"}""").map(_("location").str) shouldBe Right("SF")
  }

  // RFC 0009, Part 3, rule 1: a broken call is never {} and never a raw string
  test("partial or invalid JSON is a malformed-tool-call Left") {
    malformed("""{"location":""") shouldBe true
    malformed("{not json") shouldBe true
  }

  test("JSON that is not an object is a malformed-tool-call Left") {
    malformed("[1,2,3]") shouldBe true
    malformed("\"text\"") shouldBe true
    malformed("42") shouldBe true
    malformed("null") shouldBe true
  }

  // A model's arguments are untrusted. A document nested too deeply is refused before it is parsed,
  // because every later traversal of the value (rendering it back to the provider, `transform` into
  // a tool's argument type) recurses once per level and a StackOverflowError is not caught by `Try`
  // (#1562). Run on a 1 MB stack so the outcome does not depend on the JVM's default stack size.
  test("arguments nested too deeply are a Left, never parsed") {
    val deep = "[" * 100000 + "]" * 100000
    SmallStack.run(malformed(deep)) shouldBe Right(true)
  }

  test("an object nested 512 levels deep is parsed; nothing deeper than 512 levels is") {
    def nested(levels: Int) = """{"a":""" * (levels - 1) + "{}" + "}" * (levels - 1)
    SmallStack.run(ToolCallRules.parseArguments(nested(512)).isRight) shouldBe Right(true)
    SmallStack.run(
      ToolCallRules.parseArguments(nested(513)).left.exists(_.message.contains("more than 512 levels deep"))
    ) shouldBe Right(true)
  }
}
