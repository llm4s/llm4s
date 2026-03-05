package org.llm4s.llmconnect.streaming

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
}
