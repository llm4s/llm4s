package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.delegation.{ AncestorPermissions, AncestorRules }
import org.llm4s.agent.graph.middleware.{ PermissionRule, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentToolSpec, Operation, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.toolapi.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** `AncestorPermissions` (#2049, design 4.45): every ancestor's rules, which only narrow a subagent's calls. */
class AncestorPermissionsSpec extends AnyFlatSpec with Matchers {

  final private case class Args(text: String) derives upickle.default.ReadWriter

  private val spec =
    AgentToolSpec("risky", "risky", Schema.`object`[Args]("args").withRequiredField("text", Schema.string("t")))
  private val request = ToolCallRequest(spec, ToolCall("1", "risky", ujson.Obj("text" -> "x")))

  private def context(approved: Boolean): ToolContext =
    ToolContext(
      new RunContext(
        RunConfig(),
        RunPosition(ThreadId("child"), RunId("r"), "r/1", TaskId("1.1"), NodeId("n"), 1),
        NodeEventSink.none
      ),
      ToolCallId("1"),
      IdempotencyKey("k"),
      ThreadState.empty(Map.empty),
      approved
    )

  private val levels = Vector(
    AncestorRules(AgentId.unsafe("root"), Vector(PermissionRule.allow(Operation.Invoke, "**"))),
    AncestorRules(
      AgentId.unsafe("mid"),
      Vector(PermissionRule.ask(Operation.Invoke, "risky"), PermissionRule.allow(Operation.Invoke, "**"))
    )
  )

  private def ran(counter: AtomicInteger)(): ToolOutcome = {
    counter.incrementAndGet()
    ToolOutcome.Success(ToolContent.Json(ujson.Str("ok")))
  }

  "AncestorPermissions" should "ask once when any level asks, and pass every level once the call is approved" in {
    val count = new AtomicInteger(0)
    val guard = new AncestorPermissions(levels)
    guard.wrapToolCall(request, context(approved = false))(ran(count)) shouldBe a[ToolOutcome.NeedsApproval]
    count.get shouldBe 0
    guard.wrapToolCall(request, context(approved = true))(ran(count)) shouldBe a[ToolOutcome.Success]
    count.get shouldBe 1
  }

  it should "deny at any level, even an approved call, naming the level" in {
    val count   = new AtomicInteger(0)
    val denying = new AncestorPermissions(levels :+ AncestorRules(AgentId.unsafe("leaf"), Vector.empty))
    denying.wrapToolCall(request, context(approved = true))(ran(count)) match {
      case ToolOutcome.Error(message) =>
        message should startWith("Denied: invoke risky")
        message should include("by agent 'leaf'")
      case other => fail(s"expected a denial, got $other")
    }
    count.get shouldBe 0
  }

  it should "add no check with no levels" in {
    val count = new AtomicInteger(0)
    new AncestorPermissions(Vector.empty).wrapToolCall(request, context(approved = false))(ran(count)) shouldBe
      a[ToolOutcome.Success]
  }
}
