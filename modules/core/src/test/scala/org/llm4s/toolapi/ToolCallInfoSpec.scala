package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A `ToolFunction` handler reads the call it runs as `SafeParameterExtractor.call` (#1740). */
class ToolCallInfoSpec extends AnyFlatSpec with Matchers {

  private val call = ToolCallInfo(toolCallId = "call_1", idempotencyKey = "k" * 64)

  /** A tool that answers with the call it saw, or "none". */
  private def echo(required: Boolean): ToolFunction[Map[String, Any], String] = {
    val base   = Schema.`object`[Map[String, Any]]("args")
    val schema = if (required) base.withProperty(Schema.property("q", Schema.string("q"))) else base
    ToolBuilder[Map[String, Any], String]("echo", "Echoes the call", schema)
      .withHandler(params => Right(params.call.fold("none")(c => s"${c.toolCallId}|${c.idempotencyKey}")))
      .buildSafe()
      .fold(e => fail(e.formatted), identity)
  }

  "ToolFunction.execute(args, call)" should "hand the call to the handler" in {
    echo(required = true).execute(ujson.Obj("q" -> "x"), call) shouldBe Right(ujson.Str(s"call_1|${"k" * 64}"))
  }

  it should "hand it over for a zero-parameter tool called with null arguments too" in {
    echo(required = false).execute(ujson.Null, call) shouldBe Right(ujson.Str(s"call_1|${"k" * 64}"))
  }

  "ToolFunction.execute(args)" should "leave the call empty, as plain tool calling has none" in {
    echo(required = true).execute(ujson.Obj("q" -> "x")) shouldBe Right(ujson.Str("none"))
    new ToolRegistry(Seq(echo(required = true)))
      .execute(ToolCallRequest("echo", ujson.Obj("q" -> "x"))) shouldBe Right(ujson.Str("none"))
  }

  "SafeParameterExtractor" should "have no call unless one is given" in {
    SafeParameterExtractor(ujson.Obj()).call shouldBe None
    SafeParameterExtractor(ujson.Obj(), Some(call)).call shouldBe Some(call)
  }

  "ToolCallInfo" should "change with its setters" in {
    call.withToolCallId("call_2").toolCallId shouldBe "call_2"
    call.withIdempotencyKey("other").idempotencyKey shouldBe "other"
  }
}
