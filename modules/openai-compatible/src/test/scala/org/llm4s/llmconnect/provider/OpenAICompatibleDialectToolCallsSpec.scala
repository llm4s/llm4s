package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `OpenAICompatibleDialect.lenientToolCalls` is the tool-call parser every dialect uses unless it
 * overrides `parseToolCalls`. The `arguments` string is model output and therefore untrusted:
 * nested too deeply to parse it must become the `{}` that any other unparseable arguments become,
 * never a `StackOverflowError` (#1562). The deep cases run on a 1 MB stack, so the outcome does not
 * depend on the JVM's default stack size.
 */
final class OpenAICompatibleDialectToolCallsSpec extends AnyFlatSpec with Matchers {

  private def call(arguments: String): ujson.Value =
    ujson.Arr(
      ujson.Obj("id" -> "c1", "type" -> "function", "function" -> ujson.Obj("name" -> "f", "arguments" -> arguments))
    )

  "lenientToolCalls" should "parse well-formed arguments" in {
    OpenAICompatibleDialect.lenientToolCalls(call("""{"a":1}""")) shouldBe Seq(ToolCall("c1", "f", ujson.Obj("a" -> 1)))
  }

  it should "turn malformed arguments into an empty object" in {
    OpenAICompatibleDialect.lenientToolCalls(call("{not json")) shouldBe Seq(ToolCall("c1", "f", ujson.Obj()))
  }

  // The comparisons happen inside the small-stack thread, so no failure message renders a deep value.
  it should "turn arguments nested too deeply into an empty object instead of parsing them" in {
    val deep = "[" * 100000 + "]" * 100000
    SmallStack.run(OpenAICompatibleDialect.lenientToolCalls(call(deep)).map(_.arguments == ujson.Obj())) shouldBe
      Right(Seq(true))
  }

  it should "parse arguments nested 512 levels deep and refuse 513" in {
    val atLimit   = "[" * 512 + "]" * 512
    val overLimit = "[" * 513 + "]" * 513
    SmallStack.run(
      OpenAICompatibleDialect.lenientToolCalls(call(atLimit)).map(_.arguments.isInstanceOf[ujson.Arr])
    ) shouldBe
      Right(Seq(true))
    SmallStack.run(OpenAICompatibleDialect.lenientToolCalls(call(overLimit)).map(_.arguments == ujson.Obj())) shouldBe
      Right(Seq(true))
  }
}
