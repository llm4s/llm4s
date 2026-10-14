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

  "An envelope target" should "be denied when a deny rule reaches any target in it, even after a broader allow" in {
    val rules = Seq(PermissionRule.deny(Write, "/out/secrets/**"), PermissionRule.allow(Write, "/out/**"))
    decide(rules, false, ToolResource(Write, "/out/**")) shouldBe ToolOutcome.Error("Denied: write /out/**")
    decide(rules, true, ToolResource(Write, "/out/**")) shouldBe ToolOutcome.Error("Denied: write /out/**")
    // a narrower envelope clear of the deny is allowed
    decide(rules, false, ToolResource(Write, "/out/public/**")) shouldBe ran
  }

  it should "be allowed only when allow rules cover all of it" in {
    decide(Seq(PermissionRule.allow(Write, "/out/**")), false, ToolResource(Write, "/out/**")) shouldBe ran
    // part of the envelope is matched by no rule
    decide(Seq(PermissionRule.allow(Write, "/out/a/**")), false, ToolResource(Write, "/out/**")) shouldBe
      ToolOutcome.Error("Denied: write /out/**")
  }

  it should "be asked about when an ask rule reaches part of it, and run once approved" in {
    val rules = Seq(PermissionRule.ask(Write, "/out/private/**"), PermissionRule.allow(Write, "/out/**"))
    decide(rules, false, ToolResource(Write, "/out/**")) shouldBe
      ToolOutcome.NeedsApproval("Permission required: write /out/**")
    decide(rules, true, ToolResource(Write, "/out/**")) shouldBe ran
  }

  it should "be denied conservatively when a deny reaches only targets an earlier partial allow decided" in {
    val rules = Seq(
      PermissionRule.allow(Write, "/out/a/**"),
      PermissionRule.deny(Write, "/out/a/x"),
      PermissionRule.allow(Write, "/out/**")
    )
    // a concrete target is first match wins: /out/a/x is allowed by the first rule
    decide(rules, false, ToolResource(Write, "/out/a/x")) shouldBe ran
    // an envelope is not split by rule: the deny reaches it, so it is denied
    decide(rules, false, ToolResource(Write, "/out/**")) shouldBe ToolOutcome.Error("Denied: write /out/**")
  }

  "A spec's resourcesOf" should "return the advertised default, invoking the tool, until withResources replaces it" in {
    spec.resourcesOf(ujson.Obj()) shouldBe Vector(ToolResource(Invoke, "files"))
    spec.resourcesOf(ujson.Obj()) shouldBe Vector(spec.defaultResource)
    spec.withResources(_ => Vector(ToolResource(Read, "/a"))).resourcesOf(ujson.Obj()) shouldBe
      Vector(ToolResource(Read, "/a"))
  }

  "ToolCallRequest(spec, call)" should "carry the tool's default resource" in {
    ToolCallRequest(spec, call).resources shouldBe Vector(ToolResource(Invoke, "files"))
  }

  /** Runs `body` with the default locale set to `locale`, restoring the previous default afterwards. */
  private def inLocale[T](locale: java.util.Locale)(body: => T): T = {
    val previous = java.util.Locale.getDefault
    java.util.Locale.setDefault(locale)
    val result = scala.util.Try(body)
    java.util.Locale.setDefault(previous)
    result.get
  }

  "Operation labels, messages and globs" should "not depend on the default locale (Turkish dotless i)" in {
    val turkish = java.util.Locale.forLanguageTag("tr")
    "INVOKE".toLowerCase(turkish) should not be "invoke" // the hazard the root locale avoids
    inLocale(turkish) {
      Operation.values.map(_.label).toVector shouldBe
        Vector("read", "write", "delete", "execute", "network", "invoke")
      decide(Seq.empty, false, ToolResource(Invoke, "files")) shouldBe ToolOutcome.Error("Denied: invoke files")
      decide(Seq(PermissionRule.ask(Write, "/INDEX")), false, ToolResource(Write, "/INDEX")) shouldBe
        ToolOutcome.NeedsApproval("Permission required: write /INDEX")
      // globs match case-sensitively, by character, in every locale
      Glob("/Index/*").matches("/Index/a") shouldBe true
      Glob("/Index/*").matches("/index/a") shouldBe false
      Glob("/ı*").matches("/I") shouldBe false
    }
    java.util.Locale.getDefault should not be turkish
  }
}
