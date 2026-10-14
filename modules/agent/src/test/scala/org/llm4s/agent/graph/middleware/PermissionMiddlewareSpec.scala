package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.{ GraphTestSupport, IdempotencyKey, ThreadState, ToolCallId }
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.tool.Operation.*
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.toolapi.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PermissionMiddlewareSpec extends AnyFlatSpec with Matchers {

  private val spec = AgentToolSpec[ujson.Value]("files", "Files", Schema.`object`[ujson.Value]("files"))
  private val call = ToolCall("c1", "files", ujson.Obj())

  private def context(approved: Boolean) =
    ToolContext(
      GraphTestSupport.testRunContext(),
      ToolCallId("c1"),
      IdempotencyKey("k"),
      ThreadState.empty(Map.empty),
      approved
    )

  private val ran = ToolOutcome.Success(ujson.Str("ran"))

  private def decide(rules: Seq[PermissionRule], approved: Boolean, resources: ToolResource*): ToolOutcome =
    new PermissionMiddleware(rules)
      .wrapToolCall(ToolCallRequest(spec, call, resources.toVector), context(approved))(() => ran)

  "PermissionMiddleware" should "let the first matching rule decide" in {
    val rules = Seq(PermissionRule.deny(Write, "/etc/**"), PermissionRule.allow(Write, "/**"))
    decide(rules, false, ToolResource(Write, "/etc/passwd")) shouldBe ToolOutcome.Error("Denied: write /etc/passwd")
    decide(rules, false, ToolResource(Write, "/src/a.scala")) shouldBe ran
    decide(rules.reverse, false, ToolResource(Write, "/etc/passwd")) shouldBe ran
  }

  it should "match rules by operation" in {
    val rules = Seq(PermissionRule.allow(Read, "/**"))
    decide(rules, false, ToolResource(Read, "/a")) shouldBe ran
    decide(rules, false, ToolResource(Write, "/a")) shouldBe ToolOutcome.Error("Denied: write /a")
  }

  it should "deny a resource no rule matches" in {
    decide(Seq.empty, false, ToolResource(Invoke, "files")) shouldBe ToolOutcome.Error("Denied: invoke files")
  }

  it should "run a call only if every resource is allowed" in {
    val rules = Seq(PermissionRule.allow(Read, "/src/**"), PermissionRule.ask(Write, "/src/**"))
    decide(rules, false, ToolResource(Read, "/src/a"), ToolResource(Delete, "/src/b")) shouldBe
      ToolOutcome.Error("Denied: delete /src/b")
    decide(rules, false, ToolResource(Read, "/src/a"), ToolResource(Write, "/src/b")) shouldBe
      ToolOutcome.NeedsApproval("Permission required: write /src/b")
    // a deny wins over an ask, whichever comes first
    decide(rules, false, ToolResource(Write, "/src/b"), ToolResource(Delete, "/src/c")) shouldBe
      ToolOutcome.Error("Denied: delete /src/c")
  }

  it should "pass an Ask once the call is approved, and still deny" in {
    val rules = Seq(PermissionRule.ask(Execute, "**"), PermissionRule.deny(Network, "**"))
    decide(rules, true, ToolResource(Execute, "ls")) shouldBe ran
    decide(rules, true, ToolResource(Execute, "ls"), ToolResource(Network, "https://x")) shouldBe
      ToolOutcome.Error("Denied: network https://x")
  }

  it should "check an empty resource vector as the tool's default" in {
    decide(Seq(PermissionRule.allow(Invoke, "files")), false) shouldBe ran
    decide(Seq(PermissionRule.allow(Invoke, "other")), false) shouldBe ToolOutcome.Error("Denied: invoke files")
  }

  "ToolCallRequest(spec, call)" should "carry the tool's default resource" in {
    ToolCallRequest(spec, call).resources shouldBe Vector(ToolResource(Invoke, "files"))
  }
}
