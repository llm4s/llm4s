package org.llm4s.llmconnect.streaming

import org.llm4s.testutil.SmallStack
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingToolArgumentParserTest extends AnyFunSuite with Matchers {

  test("empty input returns empty Obj sentinel") {
    StreamingToolArgumentParser.parse("") shouldBe ujson.Obj()
  }

  test("valid JSON is parsed as-is") {
    val result = StreamingToolArgumentParser.parse("""{"location":"SF"}""")
    result("location").str shouldBe "SF"
  }

  test("partial/invalid JSON is preserved as raw Str") {
    val result = StreamingToolArgumentParser.parse("""{"location":""")
    result shouldBe ujson.Str("""{"location":""")
  }

  test("valid JSON array is parsed correctly") {
    val result = StreamingToolArgumentParser.parse("""[1,2,3]""")
    result.arr.map(_.num.toInt) shouldBe Seq(1, 2, 3)
  }

  // A model's arguments are untrusted. A document nested too deeply is refused before it is parsed,
  // because every later traversal of the value (rendering it back to the provider, `transform` into
  // a tool's argument type) recurses once per level and a StackOverflowError is not caught by `Try`
  // (#1562). Run on a 1 MB stack so the outcome does not depend on the JVM's default stack size;
  // the assertions compare inside it, so a deep value is never rendered by a failure message.
  test("arguments nested too deeply are preserved as a raw Str, not parsed") {
    val deep = "[" * 100000 + "]" * 100000
    SmallStack.run(StreamingToolArgumentParser.parse(deep) == ujson.Str(deep)) shouldBe Right(true)
  }

  test("arguments nested 512 levels deep are parsed, 513 are preserved as a raw Str") {
    val atLimit   = "[" * 512 + "]" * 512
    val overLimit = "[" * 513 + "]" * 513
    SmallStack.run(StreamingToolArgumentParser.parse(atLimit).isInstanceOf[ujson.Arr]) shouldBe Right(true)
    SmallStack.run(StreamingToolArgumentParser.parse(overLimit) == ujson.Str(overLimit)) shouldBe Right(true)
  }
}
