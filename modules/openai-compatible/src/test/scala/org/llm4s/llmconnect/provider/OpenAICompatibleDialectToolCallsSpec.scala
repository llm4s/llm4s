package org.llm4s.llmconnect.provider

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `OpenAICompatibleDialect.lenientToolCalls` is the tool-call parser every dialect uses unless it
 * overrides `parseToolCalls`. The `arguments` string is model output and therefore untrusted. Under
 * RFC 0009, Part 3, arguments that are not a JSON object are a malformed call, a `Left`, never the
 * `{}` they used to become: an agent ran a tool on that `{}`. Nested too deeply, they are a `Left`
 * without being parsed, never a `StackOverflowError` (#1562). The deep cases run on a 1 MB stack, so
 * the outcome does not depend on the JVM's default stack size.
 */
final class OpenAICompatibleDialectToolCallsSpec extends AnyFlatSpec with Matchers {

  private def call(arguments: ujson.Value): ujson.Value =
    ujson.Arr(
      ujson.Obj("id" -> "c1", "type" -> "function", "function" -> ujson.Obj("name" -> "f", "arguments" -> arguments))
    )

  private def malformed(arguments: ujson.Value): Boolean =
    OpenAICompatibleDialect.lenientToolCalls(call(arguments)) match {
      case Left(e: ProcessingError) => e.message.contains("malformed tool call 'f'")
      case _                        => false
    }

  "lenientToolCalls" should "parse well-formed arguments" in {
    OpenAICompatibleDialect.lenientToolCalls(call("""{"a":1}""")) shouldBe
      Right(Seq(ToolCall("c1", "f", ujson.Obj("a" -> 1))))
  }

  it should "read absent, null or blank arguments as {}, a call with no arguments" in {
    val absent = ujson.Arr(ujson.Obj("id" -> "c1", "function" -> ujson.Obj("name" -> "f")))
    OpenAICompatibleDialect.lenientToolCalls(absent) shouldBe Right(Seq(ToolCall("c1", "f", ujson.Obj())))
    Seq[ujson.Value](ujson.Null, "", "  ").foreach { blank =>
      OpenAICompatibleDialect.lenientToolCalls(call(blank)) shouldBe Right(Seq(ToolCall("c1", "f", ujson.Obj())))
    }
  }

  it should "fail malformed arguments, never turning them into {}" in {
    malformed("{not json") shouldBe true
    malformed("""{"a":""") shouldBe true
  }

  it should "fail arguments that are JSON but not an object" in {
    malformed("[1,2]") shouldBe true
    malformed("42") shouldBe true
    malformed(ujson.Num(42)) shouldBe true
  }

  // The comparisons happen inside the small-stack thread, so no failure message renders a deep value.
  it should "fail arguments nested too deeply without parsing them" in {
    val deep = "[" * 100000 + "]" * 100000
    SmallStack.run(malformed(deep)) shouldBe Right(true)
  }

  it should "parse an object nested 512 levels deep, and nothing deeper" in {
    def nested(levels: Int) = """{"a":""" * (levels - 1) + "{}" + "}" * (levels - 1)
    SmallStack.run(OpenAICompatibleDialect.lenientToolCalls(call(nested(512))).isRight) shouldBe Right(true)
    SmallStack.run(
      OpenAICompatibleDialect
        .lenientToolCalls(call(nested(513)))
        .left
        .exists(_.message.contains("nested more than 512 levels deep"))
    ) shouldBe Right(true)
    // a 512-deep array is within the limit, and is a Left only because it is not an object
    SmallStack.run(malformed("[" * 512 + "]" * 512)) shouldBe Right(true)
  }
}
