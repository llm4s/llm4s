package org.llm4s.agent.graph.tool

import org.llm4s.agent.graph.{ IdempotencyKey, ThreadState, ToolCallId }
import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A `ToolFunction` run by an agent sees the call's id and idempotency key (#1740). */
class FromToolFunctionCallSpec extends AnyFlatSpec with Matchers {

  private val function = ToolBuilder[Map[String, Any], String]("echo", "Echoes the call", Schema.`object`("args"))
    .withHandler(params => Right(params.call.fold("none")(c => s"${c.toolCallId}|${c.idempotencyKey}")))
    .buildSafe()
    .fold(e => fail(e.formatted), identity)

  private def context(key: String) =
    ToolContext(testRunContext(), ToolCallId("call_7"), IdempotencyKey(key), ThreadState.empty(Map.empty))

  "AgentTool.fromToolFunction" should "pass the call's id and idempotency key to the handler" in {
    AgentTool.fromToolFunction(function).execute(ujson.Obj(), context("key-a")) shouldBe
      ToolOutcome.Success(ToolContent.Json(ujson.Str("call_7|key-a")))
  }

  it should "pass the key of the context it runs with, which the runtime keeps across runs of one call" in {
    val tool = AgentTool.fromToolFunction(function)
    tool.execute(ujson.Obj(), context("key-a")) shouldBe tool.execute(ujson.Obj(), context("key-a"))
    tool.execute(ujson.Obj(), context("key-b")) shouldBe ToolOutcome.Success(
      ToolContent.Json(ujson.Str("call_7|key-b"))
    )
  }
}
